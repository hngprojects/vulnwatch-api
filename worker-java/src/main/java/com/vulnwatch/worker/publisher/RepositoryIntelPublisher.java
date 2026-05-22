package com.vulnwatch.worker.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.RepositoryIntel;
import com.vulnwatch.worker.model.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Publishes scan completion events to a Redis list.
 * The .NET API runs a BLPOP consumer on the same key to send webhook/notification events.
 *
 * Event shape:
 * {
 *   "scanId":           "...",
 *   "repoId":           "...",
 *   "requestedBy":      "...",
 *   "status":           "COMPLETED" | "FAILED",
 *   "vulnerableCount":  3,
 *   "overallSeverity":  "HIGH",
 *   "completedAt":      "2026-05-20T...",
 *   "error":            null | "message"
 * }
 */
@Component
public class RepositoryIntelPublisher {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIntelPublisher.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final String notifyQueue;
    private final ObjectMapper mapper;

    public RepositoryIntelPublisher(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${worker.notify.queue:scan-notifications}") String notifyQueue) {
        this.redisTemplate = redisTemplate;
        this.notifyQueue = notifyQueue;
        this.mapper = new ObjectMapper();
    }

    public void publishSuccess(ScanJob job, RepositoryIntel result) {
        publish(Map.of(
                "scanId",          job.scanId(),
                "repoId",          job.repoId(),
                "requestedBy",     job.requestedBy(),
                "status",          "COMPLETED",
                "vulnerableCount", result.vulnerableCount(),
                "overallSeverity", result.overallSeverity(),
                "completedAt",     Instant.now().toString(),
                "error",           null
        ));
    }

    public void publishFailure(ScanJob job, String errorMessage) {
        publish(Map.of(
                "scanId",          job.scanId(),
                "repoId",          job.repoId(),
                "requestedBy",     job.requestedBy(),
                "status",          "FAILED",
                "vulnerableCount", 0,
                "overallSeverity", "NONE",
                "completedAt",     Instant.now().toString(),
                "error",           errorMessage != null ? errorMessage : "Unknown error"
        ));
    }

    private void publish(Map<String, Object> event) {
        try {
            String json = mapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(notifyQueue, json);
            log.debug("Published event to {}: scanId={}", notifyQueue, event.get("scanId"));
        } catch (Exception e) {
            log.error("Failed to publish notification event: {}", e.getMessage(), e);
            throw new RuntimeException("Notification publish failed", e);  
        }
    }
}
