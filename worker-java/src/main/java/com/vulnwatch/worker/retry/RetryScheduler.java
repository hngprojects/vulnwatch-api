package com.vulnwatch.worker.retry;

import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.ScanRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the {@code scan:retry} Sorted Set for failed scanner jobs whose
 * next-retry timestamp is due, re-runs the specific scanner, and publishes
 * the result to {@code surface:result:stream}.
 *
 * <p><b>Atomicity:</b> the Lua script ({@code pop_and_retry.lua}) performs
 * {@code ZRANGEBYSCORE} + {@code ZREM} in a single atomic operation. By the
 * time the script returns, those keys are already removed from the ZSET — no
 * other app instance can pick up the same jobs. This is the correct place to
 * use the Lua script; no further ZSET manipulation is needed after this point.
 *
 * <p><b>Tradeoff:</b> because the ZSET entry is removed before the scanner runs,
 * a crash mid-task means that job is lost. If you need crash recovery, consider
 * a pending/processing ZSET approach, but for this system the Lua atomicity is
 * the right call to prevent duplicate processing.
 *
 * <p><b>Scanner resolution:</b> all {@link Scanner} beans are injected as a list
 * by Spring and indexed by {@link SurfaceType} at startup via {@link #buildScannerMap()}.
 * Adding a new scanner requires no changes here.
 *
 * <p><b>Cleanup:</b> after the Lua script removes the ZSET entry, the only
 * remaining cleanup is deleting the metadata Hash — done in the executor task's
 * {@code finally} block after the event is published.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List<String>> popAndRetryScript;
    private final ScanRepository scanRepository;
    private final SurfaceEventPublisher surfaceEventPublisher;
    private final ExecutorService executor;
    private final List<Scanner> scanners;

    private static final int BATCH_SIZE = 10;

    private Map<SurfaceType, Scanner> scannerMap;

    @PostConstruct
    void buildScannerMap() {
        scannerMap = scanners.stream()
                .filter(s -> s.getSurfaceType() != null)
                .collect(Collectors.toMap(
                        Scanner::getSurfaceType,
                        Function.identity(),
                        (a, b) -> {
                            log.warn("Duplicate scanner for surface {}: keeping {}",
                                    a.getSurfaceType(), a.getClass().getSimpleName());
                            return a;
                        }
                ));
        log.info("RetryScheduler: registered {} scanners: {}",
                scannerMap.size(), scannerMap.keySet());
    }



    /**
     * Runs every 5 seconds. Executes the Lua script which atomically:
     * <ol>
     *   <li>Finds jobs with score ≤ now ({@code ZRANGEBYSCORE})</li>
     *   <li>Removes them from the ZSET ({@code ZREM})</li>
     *   <li>Returns the list of retry keys</li>
     * </ol>
     * No other instance can claim the same keys after this point.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void processRetries() {
        try {
            long now = Instant.now().getEpochSecond();

            List<String> retryKeys = redisTemplate.execute(
                    popAndRetryScript,
                    List.of(RedisConfig.Keys.RETRY_ZSET),
                    String.valueOf(now),
                    String.valueOf(BATCH_SIZE)
            );

            if (retryKeys.isEmpty()) {
                return;
            }

            log.debug("RetryScheduler: {} jobs claimed for retry", retryKeys.size());
            retryKeys.forEach(this::processRetryJob);

        } catch (Exception e) {
            log.error("Error executing retry Lua script: {}", e.getMessage(), e);
        }
    }


    /**
     * Loads metadata for a single retry key and dispatches the scanner task.
     *
     * <p>If the metadata Hash is missing or the scanner/scan cannot be resolved,
     * the Hash is cleaned up and the job is dropped — the ZSET entry is already
     * gone (removed by the Lua script).
     */
    private void processRetryJob(String retryKey) {
        try {
            Map<Object, Object> metadata = redisTemplate.opsForHash().entries(retryKey);

            if (metadata.isEmpty()) {
                log.warn("Retry key {} has no metadata (ZSET entry already removed) — dropping job",
                        retryKey);
                return;
            }

            UUID scanId = UUID.fromString(metadata.get("scanId").toString());
            SurfaceType surface = SurfaceType.valueOf(metadata.get("surface").toString());
            int attempt = Integer.parseInt(metadata.get("attempt").toString());
            String rawDataKey = nullIfBlank(metadata.get("rawDataKey"));

            Scanner scanner = scannerMap.get(surface);
            if (scanner == null) {
                log.error("No scanner registered for surface {} (scan={}) — dropping job",
                        surface, scanId);
                deleteHash(retryKey);
                return;
            }

            Scan scan = scanRepository.findById(scanId).orElse(null);
            if (scan == null) {
                log.error("Scan {} not found — dropping retry job for surface {}", scanId, surface);
                deleteHash(retryKey);
                return;
            }

            submitRetryTask(retryKey, scanId, surface, scanner,
                    buildScanJob(scan), attempt, rawDataKey);

        } catch (Exception e) {
            log.error("Failed to dispatch retry job {}: {}", retryKey, e.getMessage(), e);
            // Hash may or may not have been read — attempt cleanup defensively
            deleteHash(retryKey);
        }
    }

    /**
     * Runs the scanner in the shared executor.
     *
     * <p>Hash cleanup happens in {@code finally} — after the event is published,
     * regardless of whether the scan succeeded or failed. The ZSET entry is
     * already gone at this point (removed by the Lua script before this method
     * is called).
     */
    private void submitRetryTask(String retryKey, UUID scanId, SurfaceType surface,
                                 Scanner scanner, ScanJob job, int attempt, String rawDataKey) {
        executor.submit(() -> {
            String scannerName = scanner.getClass().getSimpleName();
            log.debug("Retry: running {} for scan={} surface={} attempt={}",
                    scannerName, scanId, surface, attempt);

            try {
                long start = System.currentTimeMillis();
                ScanResult result = scanner.scan(job);
                long durationMs = System.currentTimeMillis() - start;

                SurfaceResultEvent event = SurfaceResultEvent
                        .success(scanId, surface, result.getRawData(), attempt, durationMs);

                if (rawDataKey != null) {
                    event.setRawDataKey(rawDataKey);
                }

                surfaceEventPublisher.publish(event);
                log.info("Retry succeeded: scanner={} scan={} surface={} attempt={}",
                        scannerName, scanId, surface, attempt);

            } catch (Exception e) {
                log.error("Retry failed: scanner={} scan={} surface={} attempt={}: {}",
                        scannerName, scanId, surface, attempt, e.getMessage());

                SurfaceResultEvent event = SurfaceResultEvent
                        .failure(scanId, surface, e.getMessage(), attempt);

                if (rawDataKey != null) {
                    event.setRawDataKey(rawDataKey);
                }

                surfaceEventPublisher.publish(event);

            } finally {
                // ZSET entry already removed by Lua script — only Hash needs cleanup
                deleteHash(retryKey);
            }
        });
    }


    private ScanJob buildScanJob(Scan scan) {
        return ScanJob.builder()
                .scanId(scan.getId())
                .requestedBy(scan.getUserId())
                .domain(getDomainName(scan))
                .scanTypes(Collections.singletonList(scan.getTargetType()))
                .enqueuedAt(Instant.now())
                .build();
    }

    private void deleteHash(String retryKey) {
        try {
            redisTemplate.delete(retryKey);
        } catch (Exception e) {
            log.warn("Failed to delete metadata hash {}: {}", retryKey, e.getMessage());
        }
    }

    private String nullIfBlank(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private String getDomainName(Scan scan) {
        return null; //TODO
    }

    public long getRetryQueueSize() {
        Long size = redisTemplate.opsForZSet().size(RedisConfig.Keys.RETRY_ZSET);
        return size != null ? size : 0;
    }
}