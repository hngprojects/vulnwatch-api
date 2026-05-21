package com.vulnwatch.worker.publisher;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.DomainIntel;
import com.vulnwatch.worker.model.RepositoryIntel;
import com.vulnwatch.worker.model.ScanJob;

@Component
public class DomainIntelPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(DomainIntelPublisher.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final String resultQueue;
    private final ObjectMapper mapper;

    public DomainIntelPublisher(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${worker.domain.result.queue:scan-results}") String resultQueue) {
        this.redisTemplate = redisTemplate;
        this.resultQueue = resultQueue;
        this.mapper = new ObjectMapper();
    }

    public void publishSuccess(ScanJob job, DomainIntel result) {
        publish(Map.of(
                "scanId",          job.scanId(),
                "domainId",        job.domainId(),
                "domainName",      job.domainName(),
                "requestedBy",     job.requestedBy(),
                "status",          "COMPLETED",
                "securityScore", result.securityScore(),
                "completedAt",     Instant.now().toString(),
                "error",           ""
        ));
    }

    public void publishFailure(ScanJob job, String errorMessage) {
        publish(Map.of(
                "scanId",          job.scanId(),
                "domainId",        job.domainId(),
                "domainName",      job.domainName(),
                "requestedBy",     job.requestedBy(),
                "status",          "FAILED",
                "securityScore",   "NONE",
                "completedAt",     Instant.now().toString(),
                "error",           errorMessage != null ? errorMessage : "Unknown error"
        ));
    }


    private void publish(Map<String, Object> event) {
        try {
            String json = mapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(resultQueue, json);
            log.debug("Published event to {}: scanId={}", resultQueue, event.get("scanId"));
        } catch (Exception e) {
            log.error("Failed to publish notification event: {}", e.getMessage(), e);
        }
    }



    // public void publish(ScanResult result) {
    //     try {
    //         String payload = mapper.writeValueAsString(result);
    //         jedis.lpush(queue, payload);
    //         System.out.printf("Published scan result for scan %s to %s%n",
    //             result.scanId(), queue);
    //     } catch (Exception e) {
    //         System.err.println("Failed to publish scan result: " + e.getMessage());
    //         e.printStackTrace();
    //     }
    // }
}
