package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.SurfaceResultEvent;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.exception.SurfacePublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SurfaceEventPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String RAW_DATA_KEY_PATTERN = "scan:%s:raw:%s";

    public void publish(SurfaceResultEvent event) {
        SurfaceResultEvent streamEvent = offloadRawDataIfPresent(event);
        pushToStream(streamEvent);

        log.debug("Published surface event: scanId={}, surface={}, success={}, eventId={}",
                event.getScanId(), event.getSurface(), event.isSuccess(), event.getEventId());
    }

    public void publishScanFailed(UUID scanId, String reason) {
        SurfaceResultEvent event = SurfaceResultEvent.scanFailed(scanId, reason);
        publish(event);
    }

    /**
     * If rawData is present (Map), convert it to JSON string and offload to Redis Hash.
     */
    private SurfaceResultEvent offloadRawDataIfPresent(SurfaceResultEvent event) {
        Map<String, Object> rawData = event.getRawData();
        if (rawData == null || rawData.isEmpty()) {
            return event;
        }

        String rawKey = String.format(RAW_DATA_KEY_PATTERN,
                event.getScanId(), event.getSurface().name());

        try {

            String rawDataJson = objectMapper.writeValueAsString(rawData);
            redisTemplate
                    .opsForHash()
                    .put(rawKey, "data", rawDataJson);
            redisTemplate
                    .expire(rawKey, java.time.Duration.ofHours(24));

            log.debug("Offloaded raw data for scan {} surface {} to key {}",
                    event.getScanId(), event.getSurface(), rawKey);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize raw data for offload", e);
            // Continue without offloading, event will have raw data in stream
            return event;
        }

        // Return lightweight copy with rawData removed
        return event.toBuilder()
                .rawData(null)
                .rawDataKey(rawKey)
                .build();
    }

    private void pushToStream(SurfaceResultEvent event) {
        try {
            // Convert entire event to JSON (rawData is null if offloaded)
            String json = objectMapper.writeValueAsString(event);

            Map<String, String> body = new HashMap<>();
            body.put("event", json);

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .in(RedisConfig.Keys.SURFACE_RESULT_STREAM)
                    .ofMap(body)
                    .withId(RecordId.autoGenerate());

            redisTemplate.opsForStream().add(record);

        } catch (JsonProcessingException e) {
            throw new SurfacePublishException(
                    "Failed to serialise surface event for scan " + event.getScanId(), e);
        }
    }


}