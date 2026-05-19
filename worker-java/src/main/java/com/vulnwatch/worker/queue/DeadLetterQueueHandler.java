package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages dead-letter queue for permanently failed scanner jobs.
 *
 * <p>Jobs end up here after exhausting all retries. An operator can inspect,
 * replay (retry), or delete entries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterQueueHandler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String DLQ_KEY = RedisConfig.Keys.DEAD_LETTER_LIST;

    /**
     * Moves a permanently failed job to the dead-letter queue.
     * Stores the original event with metadata for later replay.
     *
     * @param event The failed surface event
     */
    public void moveToDeadLetter(SurfaceResultEvent event) {
        try {
            String dlqEntry = buildDlqEntry(event);
            redisTemplate.opsForList().leftPush(DLQ_KEY, dlqEntry);

            log.warn("Moved to DLQ: scanId={}, surface={}, attempt={}, error={}",
                    event.getScanId(), event.getSurface(), event.getAttempt(), event.getErrorMessage());

        } catch (Exception e) {
            log.error("Failed to move job to DLQ for scanId={}, surface={}",
                    event.getScanId(), event.getSurface(), e);
        }
    }

    /**
     * Returns all entries in the dead-letter queue parsed into strongly-typed Maps.
     *
     * @return List of DLQ entries (each as a Map with metadata)
     */
    public List<Map<String, Object>> listDeadLetters() {
        List<Object> rawList = redisTemplate.opsForList().range(DLQ_KEY, 0, -1);
        if (rawList == null) {
            return List.of();
        }

        return rawList.stream()
                .map(raw -> {
                    try {
                        return objectMapper.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.error("Failed to parse DLQ entry payload from list: {}", e.getMessage());
                        return Map.<String, Object>of();
                    }
                })
                .filter(map -> !map.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Gets a single DLQ entry by index.
     *
     * @param index Position in the list (0 = most recent)
     * @return DLQ entry as a Map, or null if not found
     */
    public Map<String, Object> getDeadLetter(int index) {
        Object entry = redisTemplate.opsForList().index(DLQ_KEY, index);
        if (entry == null) {
            return null;
        }
        try {
            return objectMapper.readValue(entry.toString(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse single DLQ entry at index {}: {}", index, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes a dead-letter entry without replaying.
     *
     * @param index Position to delete
     */
    public void deleteDeadLetter(int index) {
        Object entry = redisTemplate.opsForList().index(DLQ_KEY, index);
        if (entry != null) {
            redisTemplate.opsForList().remove(DLQ_KEY, 1, entry);
            log.info("Deleted DLQ entry at index {}", index);
        }
    }

    /**
     * Replays a dead-letter job back to the result stream.
     * The original event is reconstructed and republished with attempt reset to 0.
     * When processed successfully, the ResultConsumer will replace the old scanner‑error finding.
     *
     * @param index Position of the DLQ entry to replay
     * @return true if replay was initiated, false if entry not found or invalid
     */
    public boolean replayDeadLetter(int index) {
        Object rawEntry = redisTemplate.opsForList().index(DLQ_KEY, index);
        if (rawEntry == null) {
            log.warn("No DLQ entry found at index {}", index);
            return false;
        }

        try {
            String entryJson = rawEntry.toString();
            // FIX: Added TypeReference to eliminate raw assignment warnings
            Map<String, Object> entry = objectMapper.readValue(entryJson, new TypeReference<Map<String, Object>>() {});

            // Reconstruct the original event
            SurfaceResultEvent originalEvent = reconstructEvent(entry);

            if (originalEvent == null) {
                log.error("Failed to reconstruct event from DLQ entry at index {}", index);
                return false;
            }

            // Reset attempt to 0 – start fresh
            SurfaceResultEvent retryEvent = originalEvent.forRetry(0);

            // Publish back to stream
            publishToStream(retryEvent);

            // Remove from DLQ after successful replay
            redisTemplate.opsForList().remove(DLQ_KEY, 1, rawEntry);

            log.info("Replayed job from DLQ: scanId={}, surface={}, attempt reset to 0",
                    retryEvent.getScanId(), retryEvent.getSurface());

            return true;

        } catch (Exception e) {
            log.error("Failed to replay DLQ entry at index {}", index, e);
            return false;
        }
    }

    /**
     * Replays all dead-letter entries.
     *
     * @return Number of entries replayed
     */
    public int replayAllDeadLetters() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        if (size == null || size == 0) {
            log.info("DLQ is empty, nothing to replay");
            return 0;
        }

        int replayed = 0;
        long totalCount = size;
        for (int i = 0; i < totalCount; i++) {
            if (replayDeadLetter(0)) { // Always take first element because list shifts
                replayed++;
            }
        }

        log.info("Replayed {} jobs from DLQ", replayed);
        return replayed;
    }

    /**
     * Builds a JSON entry for the dead-letter queue.
     */
    private String buildDlqEntry(SurfaceResultEvent event) throws JsonProcessingException {
        Map<String, Object> entry = new HashMap<>();
        entry.put("scanId", event.getScanId().toString());
        entry.put("surface", event.getSurface().name());
        entry.put("attempt", event.getAttempt());
        entry.put("errorMessage", event.getErrorMessage());
        entry.put("failedAt", Instant.now().toString());
        entry.put("rawDataKey", event.getRawDataKey());
        entry.put("eventJson", objectMapper.writeValueAsString(event));

        return objectMapper.writeValueAsString(entry);
    }

    /**
     * Reconstructs SurfaceResultEvent from DLQ entry.
     */
    private SurfaceResultEvent reconstructEvent(Map<String, Object> entry) {
        try {
            String eventJson = (String) entry.get("eventJson");
            if (eventJson != null) {
                return objectMapper.readValue(eventJson, SurfaceResultEvent.class);
            }

            // Fallback: reconstruct from fields
            UUID scanId = UUID.fromString((String) entry.get("scanId"));
            SurfaceType surface = SurfaceType.valueOf((String) entry.get("surface"));
            int attempt = (int) entry.get("attempt");
            String errorMessage = (String) entry.get("errorMessage");
            String rawDataKey = (String) entry.get("rawDataKey");

            SurfaceResultEvent event = SurfaceResultEvent.failure(scanId, surface, errorMessage, attempt);
            if (rawDataKey != null && !rawDataKey.isBlank()) {
                event.setRawDataKey(rawDataKey);
            }
            return event;

        } catch (Exception e) {
            log.error("Failed to reconstruct event from DLQ entry", e);
            return null;
        }
    }

    /**
     * Publishes event to Redis Stream for ResultConsumer to pick up.
     */
    private void publishToStream(SurfaceResultEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);

            Map<String, String> body = new HashMap<>();
            body.put("event", json);

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .in(RedisConfig.Keys.SURFACE_RESULT_STREAM)
                    .ofMap(body)
                    .withId(RecordId.autoGenerate());

            redisTemplate.opsForStream().add(record);

            log.debug("Published event to stream: scanId={}, surface={}, attempt={}",
                    event.getScanId(), event.getSurface(), event.getAttempt());

        } catch (JsonProcessingException e) {
            log.error("Failed to publish event to stream", e);
        }
    }
}
