package com.vulnwatch.worker.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.ai.FallbackResultCreator;
import com.vulnwatch.worker.ai.ScoreCalculator;
import com.vulnwatch.worker.circuitbreaker.OpenAiCircuitBreaker;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import com.vulnwatch.worker.queue.DeadLetterQueueHandler;
import com.vulnwatch.worker.queue.ScanCompletionPublisher;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.repository.ScanRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResultConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SurfaceStateManager stateManager;
    private final OpenAiCircuitBreaker circuitBreaker;
    private final ScanCompletionPublisher completionPublisher;
    private final DeadLetterQueueHandler dlqHandler;
    private final FindingRepository findingRepository;
    private final ScanRepository scanRepository;
    private final ScoreCalculator scoreCalculator;
    private final FallbackResultCreator fallbackCreator;
    private final ExecutorService consumerExecutor;

    @Value("${scan.max-retries:3}")
    private int maxRetries;

    @Value("${consumer.shutdown-timeout-seconds:15}")
    private int shutdownTimeoutSeconds;

    private static final String CONSUMER_NAME = "worker-" + UUID.randomUUID();
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<UUID, List<String>> fallbackSurfacesMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        ensureConsumerGroup();
        running.set(true);
        consumerExecutor.submit(this::consumeLoop);
        log.info("ResultConsumer started — consumer={}, group={}",
                CONSUMER_NAME, RedisConfig.CONSUMER_GROUP);
    }

    @PreDestroy
    public void stop() {
        log.info("ResultConsumer shutting down...");
        running.set(false);
        consumerExecutor.shutdown();
        try {
            if (!consumerExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Consumer did not terminate cleanly — forcing shutdown");
                consumerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            consumerExecutor.shutdownNow();
        }
        log.info("ResultConsumer stopped");
    }

    // ==================== CONSUME LOOP ====================

    private void consumeLoop() {
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = poll();
                records.forEach(this::processAndAck);
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error in consume loop: {}", e.getMessage(), e);
                    sleepQuietly(Duration.ofSeconds(1));
                }
            }
        }
    }

    private List<MapRecord<String, Object, Object>> poll() {
        var consumer = Consumer.from(RedisConfig.CONSUMER_GROUP, CONSUMER_NAME);
        var readOptions = StreamReadOptions.empty().block(BLOCK_TIMEOUT).count(10);
        var streamOffset = StreamOffset.create(RedisConfig.Keys.SURFACE_RESULT_STREAM, ReadOffset.lastConsumed());

        @SuppressWarnings("unchecked")
        List<MapRecord<String, Object, Object>> records = redisTemplate
                .opsForStream()
                .read(consumer, readOptions, streamOffset);

        return Objects.requireNonNullElse(records, List.of());
    }

    private void processAndAck(MapRecord<String, Object, Object> record) {
        try {
            processRecord(record);
            acknowledge(record);
        } catch (Exception e) {
            log.error("Failed to process record {}, leaving unacked: {}",
                    record.getId(), e.getMessage(), e);
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(
                RedisConfig.Keys.SURFACE_RESULT_STREAM,
                RedisConfig.CONSUMER_GROUP,
                record.getId());
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get("event");
        if (raw == null) {
            log.warn("Record {} has no 'event' field, skipping", record.getId());
            return;
        }

        SurfaceResultEvent event;
        try {
            event = objectMapper.readValue(raw.toString(), SurfaceResultEvent.class);
        } catch (Exception e) {
            log.error("Cannot deserialise event from record {}: {}", record.getId(), e.getMessage());
            return;
        }

        if (isAlreadyProcessed(event)) {
            log.debug("Duplicate eventId={} skipped", event.getEventId());
            return;
        }

        log.debug("Processing: scanId={}, surface={}, success={}, attempt={}",
                event.getScanId(), event.getSurface(), event.isSuccess(), event.getAttempt());

        if (event.isSuccess()) {
            handleSuccess(event);
        } else {
            handleFailure(event);
        }
    }


    private void handleSuccess(SurfaceResultEvent event) {
        UUID scanId = event.getScanId();
        SurfaceType surface = event.getSurface();

        // Delete any previous fallback findings
        findingRepository.deleteByScanIdAndSurface(scanId, surface);

        stateManager.updateSuccess(scanId, surface);

        AggregatedScanData aggregatedData = buildAggregatedData(scanId, surface, resolveRawData(event));

        EnrichedScanResult enriched = circuitBreaker.enrichWithCircuitBreaker(aggregatedData);

        // Save findings to database
        if (enriched.getFindings() != null && !enriched.getFindings().isEmpty()) {
            enriched.getFindings().forEach(f -> f.setScanId(scanId));
            findingRepository.saveAll(enriched.getFindings());
            log.info("Saved {} findings for scanId={}, surface={}",
                    enriched.getFindings().size(), scanId, surface);
        }

        // Calculate and accumulate score
        int surfaceScore = scoreCalculator.calculateFromDbFindings(enriched.getFindings());
        accumulateSurfaceScore(scanId, surface, surfaceScore);

        // Track fallback surfaces for final completion
        if (enriched.isFallback()) {
            log.warn("FALLBACK used for scan {} surface {}: {}", scanId, surface, enriched.getFallbackReason());
            fallbackSurfacesMap.computeIfAbsent(scanId, k -> new ArrayList<>()).add(surface.name());
        }

        checkAndPublishCompletion(scanId);
    }


    private void handleFailure(SurfaceResultEvent event) {
        UUID scanId = event.getScanId();
        SurfaceType surface = event.getSurface();
        int nextAttempt = event.getAttempt() + 1;

        if (nextAttempt <= maxRetries) {
            long delaySeconds = calculateBackoffSeconds(nextAttempt);

            // Update state
            stateManager.updateRetrying(scanId, surface, nextAttempt, event.getErrorMessage());

            // Store in retry ZSET (RetryScheduler picks up independently)
            writeToRetryZset(event, nextAttempt, delaySeconds);

            log.warn("Surface {} failed for scan {}: attempt {} of {}, retry in {}s",
                    surface, scanId, nextAttempt, maxRetries, delaySeconds);
        } else {
            // Max retries exhausted
            stateManager.updatePermanentlyFailed(scanId, surface, event.getErrorMessage());

            // Save scanner-error finding
            Finding failureFinding = fallbackCreator.createScannerFailureFinding(scanId, surface, event.getErrorMessage());
            failureFinding.setScanId(scanId);
            findingRepository.save(failureFinding);

            // Move to DLQ for manual inspection
            dlqHandler.moveToDeadLetter(event);

            // Neutral score for failed surface
            accumulateSurfaceScore(scanId, surface, 50);

            log.error("Surface {} permanently failed for scan {} after {} attempts — moved to DLQ",
                    surface, scanId, maxRetries);

            checkAndPublishCompletion(scanId);
        }
    }


    private void accumulateSurfaceScore(UUID scanId, SurfaceType surface, int score) {
        String key = "scan:" + scanId + ":scores";
        redisTemplate.opsForHash().put(key, surface.name(), String.valueOf(score));
        redisTemplate.expire(key, Duration.ofHours(24));
        log.debug("Accumulated score for scan {} surface {}: {}", scanId, surface, score);
    }

    private int calculateOverallScore(UUID scanId) {
        String key = "scan:" + scanId + ":scores";
        Map<Object, Object> scores = redisTemplate.opsForHash().entries(key);

        if (scores.isEmpty()) {
            return 0;
        }

        int total = 0;
        int count = 0;
        for (Object value : scores.values()) {
            try {
                total += Integer.parseInt(value.toString());
                count++;
            } catch (NumberFormatException e) {
                log.warn("Invalid score value for scan {}: {}", scanId, value);
            }
        }

        return count > 0 ? total / count : 0;
    }


    private void checkAndPublishCompletion(UUID scanId) {
        if (!stateManager.isAllTerminal(scanId)) {
            return;
        }

        List<String> failed = stateManager.getFailedSurfaces(scanId);
        List<String> succeeded = stateManager.getSuccessfulSurfaces(scanId);

        ScanStatus overallStatus = determineScanStatus(succeeded, failed);
        int overallScore = calculateOverallScore(scanId);
        int totalFindings = (int) findingRepository.countByScanId(scanId);

        // Get fallback surfaces and remove from map
        List<String> fallbackSurfaces = fallbackSurfacesMap.remove(scanId);
        if (fallbackSurfaces == null) {
            fallbackSurfaces = List.of();
        }

        // Update scan entity
        scanRepository.findById(scanId).ifPresent(scan -> {
            scan.setSecurityScore(overallScore);
            scanRepository.save(scan);
        });

        completionPublisher.publishCompletion(scanId, overallStatus, overallScore, totalFindings, fallbackSurfaces);

        log.info("Scan {} completed: status={}, score={}, findings={}, fallback={}, failed={}",
                scanId, overallStatus, overallScore, totalFindings, fallbackSurfaces, failed);

        // Clean up
        String scoresKey = "scan:" + scanId + ":scores";
        redisTemplate.delete(scoresKey);
    }

    private ScanStatus determineScanStatus(List<String> succeeded, List<String> failed) {
        if (succeeded.isEmpty()) {
            return ScanStatus.FAILED;
        }
        return ScanStatus.COMPLETED;
    }


    private AggregatedScanData buildAggregatedData(UUID scanId, SurfaceType surface, Map<String, Object> rawData) {
        ScanResult result = ScanResult.success(scanId, surface.name() + "Scanner", surface, rawData);
        return AggregatedScanData.builder()
                .scanId(scanId)
                .successfulResults(List.of(result))
                .failures(List.of())
                .build();
    }

    private Map<String, Object> resolveRawData(SurfaceResultEvent event) {
        if (event.hasRawData()) {
            return event.getRawData();
        }

        if (event.hasRawDataKey()) {
            Map<Object, Object> stored = redisTemplate.opsForHash().entries(event.getRawDataKey());
            if (stored.get("data") instanceof String json) {
                try {
                    return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.error("Failed to parse offloaded rawData from Redis key [{}]: {}",
                            event.getRawDataKey(), e.getMessage());
                }
            }
        }

        return Map.of();
    }

    private void writeToRetryZset(SurfaceResultEvent originalEvent, int nextAttempt, long delaySeconds) {
        String retryKey = "retry:job:" + originalEvent.getScanId() + ":" + originalEvent.getSurface().name();
        long nextRetryAt = Instant.now().getEpochSecond() + delaySeconds;

        try {
            SurfaceResultEvent retryEvent = originalEvent.forRetry(nextAttempt);
            String eventJson = objectMapper.writeValueAsString(retryEvent);

            Map<String, String> metadata = Map.of(
                    "scanId", originalEvent.getScanId().toString(),
                    "surface", originalEvent.getSurface().name(),
                    "attempt", String.valueOf(nextAttempt),
                    "lastError", originalEvent.getErrorMessage() != null ? originalEvent.getErrorMessage() : "",
                    "rawDataKey", originalEvent.getRawDataKey() != null ? originalEvent.getRawDataKey() : "",
                    "eventJson", eventJson
            );

            redisTemplate.opsForHash().putAll(retryKey, metadata);
            redisTemplate.expire(retryKey, Duration.ofHours(24));
            redisTemplate.opsForZSet().add(RedisConfig.Keys.RETRY_ZSET, retryKey, nextRetryAt);

            log.debug("Retry scheduled: scanId={}, surface={}, attempt={}, delay={}s",
                    originalEvent.getScanId(), originalEvent.getSurface(), nextAttempt, delaySeconds);

        } catch (Exception e) {
            log.error("Failed to schedule retry for scanId={}, surface={}",
                    originalEvent.getScanId(), originalEvent.getSurface(), e);
        }
    }

    private boolean isAlreadyProcessed(SurfaceResultEvent event) {
        String key = "scan:" + event.getScanId() + ":processed";
        Long added = redisTemplate.opsForSet().add(key, event.getEventId().toString());
        if (added != null && added > 0) {
            redisTemplate.expire(key, Duration.ofHours(24));
            return false;
        }
        return true;
    }

    private long calculateBackoffSeconds(int attempt) {
        long base = 5L * (1L << (attempt - 1));
        double jitter = 0.8 + (Math.random() * 0.4);
        return Math.max(1L, Math.round(base * jitter));
    }

    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(
                    RedisConfig.Keys.SURFACE_RESULT_STREAM,
                    ReadOffset.latest(),
                    RedisConfig.CONSUMER_GROUP
            );
            log.info("Created consumer group: {}", RedisConfig.CONSUMER_GROUP);
        } catch (Exception e) {
            log.debug("Consumer group already exists: {}", e.getMessage());
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}