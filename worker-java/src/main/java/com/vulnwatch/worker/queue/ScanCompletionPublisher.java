package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.exception.ScanPublishException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes final scan completion or failure events to the Redis List
 * {@code scan:results} for consumption by the C# API.
 *
 * <p>This is one of two publishers in the system — do not confuse with
 * {@link com.vulnwatch.worker.queue.SurfaceEventPublisher}, which publishes
 * per-scanner events to the Redis Stream for internal Java consumption.
 *
 * <p>Message format:
 * <pre>
 * {
 *   "scanId": "uuid",
 *   "status": "completed" | "partial_failure" | "failed",
 *   "securityScore": 72, // null on failure
 *   "findingCount": 5,
 *   "processedAt": "2026-05-17T10:00:35Z"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanCompletionPublisher {

  private final RedisTemplate<String, Object> redisTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Publishes a successful (or partially successful) scan completion.
   * If publishing fails due to an underlying exception, it catches it and handles
   * a fallback message to alert the consumer that the scan failed.
   *
   * @param scanId the scan identifier
   * @param status expected to be COMPLETED or PARTIAL_FAILURE
   * @param securityScore aggregated score across all surfaces
   * @param findingCount total findings stored
   */
  public void publishCompletion(UUID scanId, ScanStatus status, int securityScore,
                                int findingCount, List<String> fallbackSurfaces) {
    CompletionMessage message = CompletionMessage.builder()
            .scanId(scanId)
            .status(status.name().toLowerCase())
            .securityScore(securityScore)
            .findingCount(findingCount)
            .hasFallback(!fallbackSurfaces.isEmpty())
            .fallbackSurfaces(fallbackSurfaces)
            .processedAt(Instant.now())
            .build();

    pushToList(message);

    if (!fallbackSurfaces.isEmpty()) {
      log.info("Scan {} completed with fallback on surfaces: {}", scanId, fallbackSurfaces);
    } else {
      log.info("Published completion: scanId={}, status={}, score={}, findings={}",
              scanId, status, securityScore, findingCount);
    }
  }

  /**
   * Publishes a scan failure (startup failure or unrecoverable error).
   * Security score is omitted — C# should treat a null score as unavailable.
   *
   * @param scanId the scan identifier
   * @param errorMessage human-readable reason for failure
   */
  public void publishScanFailed(UUID scanId, String errorMessage) {
    CompletionMessage message = CompletionMessage.builder()
            .scanId(scanId)
            .status(ScanStatus.FAILED.getDisplayName())
            .securityScore(null)
            .findingCount(0)
            .processedAt(Instant.now())
            .build();

    pushToList(message);
    log.warn("Published failure: scanId={}, reason={}", scanId, errorMessage);
  }

  /**
   * Pushes message to Redis List. Wraps serialization and transport failures.
   */
  private void pushToList(CompletionMessage message) {
    try {
      String json = objectMapper.writeValueAsString(message);
      redisTemplate.opsForList().leftPush(RedisConfig.Keys.SCAN_RESULTS_LIST, json);
    } catch (JsonProcessingException e) {
      throw new ScanPublishException(
              "Failed to serialise completion message for scan " + message.getScanId(), e);
    } catch (Exception e) {
      throw new ScanPublishException(
              "Redis transport failure while pushing message for scan " + message.getScanId(), e);
    }
  }

  @Data
  @Builder
  private static class CompletionMessage {
    private UUID scanId;
    private String status;
    private Integer securityScore;
    private int findingCount;
    private boolean hasFallback;
    private List<String> fallbackSurfaces;
    private Instant processedAt;
  }
}
