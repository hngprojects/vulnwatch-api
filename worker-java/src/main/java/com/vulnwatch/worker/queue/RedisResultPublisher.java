package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.ScanStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes scan completion status to Redis queue for C# API consumption.
 *
 * <p>Message format published to Redis "scan-results" queue:
 *
 * <pre>
 * {
 *   "scanId": "uuid",
 *   "status": "completed|failed",
 *   "securityScore": 72,
 *   "findingCount": 5,
 *   "processedAt": "2026-05-12T10:00:35Z"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisResultPublisher {

  private final RedisTemplate<String, Object> redisTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Publishes a successful scan completion to Redis.
   *
   * @param scanId The ID of the completed scan
   * @param status The final status (COMPLETED)
   * @param securityScore The security score (0-100)
   * @param findingCount Number of findings generated
   */
  public void publishCompletion(
      UUID scanId, ScanStatus status, int securityScore, int findingCount) {
    ScanResultMessage message =
        ScanResultMessage.builder()
            .scanId(scanId)
            .status(status.getDisplayName())
            .securityScore(securityScore)
            .findingCount(findingCount)
            .processedAt(Instant.now())
            .build();

    publish(message);
  }

  /**
   * Publishes a scan failure to Redis.
   *
   * @param scanId The ID of the failed scan
   * @param errorMessage The error message (logged but not sent to Redis)
   */
  public void publishFailure(UUID scanId, String errorMessage) {
    ScanResultMessage message =
        ScanResultMessage.builder()
            .scanId(scanId)
            .status(ScanStatus.FAILED.getDisplayName())
            .securityScore(null)
            .findingCount(0)
            .processedAt(Instant.now())
            .build();

    publish(message);
    log.debug("Failure for scan {}: {}", scanId, errorMessage);
  }

  /** Internal method to serialize and push notification to Redis. */
  private void publish(ScanResultMessage message) {
    try {
      String json = objectMapper.writeValueAsString(message);
      redisTemplate.opsForList().leftPush(RedisConfig.Keys.SCAN_QUEUE, json);
      log.info(
          "Published to {}: scanId={}, status={}, score={}, findings={}",
          RedisConfig.Keys.SCAN_QUEUE,
          message.getScanId(),
          message.getStatus(),
          message.getSecurityScore(),
          message.getFindingCount());
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize scan result message: {}", e.getMessage());
    }
  }

  /** Internal DTO for Redis message. */
  @lombok.Builder
  @lombok.Data
  private static class ScanResultMessage {
    private UUID scanId;
    private String status;
    private Integer securityScore;
    private int findingCount;
    private Instant processedAt;
  }
}
