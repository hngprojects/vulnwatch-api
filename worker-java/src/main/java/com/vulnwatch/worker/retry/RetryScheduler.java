package com.vulnwatch.worker.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 10;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void processRetries() {
        long now = Instant.now().getEpochSecond();

        Set<Object> retryKeys = redisTemplate.opsForZSet()
                .rangeByScore(RedisConfig.Keys.RETRY_ZSET, 0, now, 0, BATCH_SIZE);

        if (retryKeys == null || retryKeys.isEmpty()) {
            return;
        }

        log.debug("Found {} jobs ready for retry", retryKeys.size());

        for (Object keyObj : retryKeys) {
            String retryKey = keyObj.toString();
            republishJob(retryKey);
        }
    }

    private void republishJob(String retryKey) {
        try {
            // Get metadata from Hash
            Map<Object, Object> metadata = redisTemplate.opsForHash().entries(retryKey);

            if (metadata.isEmpty()) {
                log.warn("No metadata for {}, cleaning up", retryKey);
                cleanup(retryKey);
                return;
            }

            String eventJson = (String) metadata.get("eventJson");
            if (eventJson == null || eventJson.isBlank()) {
                log.warn("No eventJson for {}, cleaning up", retryKey);
                cleanup(retryKey);
                return;
            }

            SurfaceResultEvent event = objectMapper.readValue(eventJson, SurfaceResultEvent.class);

            publishToStream(event);
            cleanup(retryKey);

            log.debug("Republished retry: scanId={}, surface={}, attempt={}",
                    event.getScanId(), event.getSurface(), event.getAttempt());

        } catch (Exception e) {
            log.error("Failed to republish retry job: {}", retryKey, e);
        }
    }

    private void publishToStream(SurfaceResultEvent event) throws Exception {
        String json = objectMapper.writeValueAsString(event);

        Map<String, String> body = new HashMap<>();
        body.put("event", json);

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(RedisConfig.Keys.SURFACE_RESULT_STREAM)
                .ofMap(body)
                .withId(RecordId.autoGenerate());

        redisTemplate.opsForStream().add(record);
    }

    private void cleanup(String retryKey) {
        redisTemplate.opsForZSet().remove(RedisConfig.Keys.RETRY_ZSET, retryKey);
        redisTemplate.delete(retryKey);
    }

    public long getRetryQueueSize() {
        Long size = redisTemplate.opsForZSet().size(RedisConfig.Keys.RETRY_ZSET);
        return size != null ? size : 0;
    }
}