package com.vulnwatch.worker.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.model.ScanResult;

public class ScanResultPublisher {

    private static final String QUEUE = "scan-results";
    private final ObjectMapper mapper = new ObjectMapper();

    public void publish(ScanResult result) {
        try {
            String payload = mapper.writeValueAsString(result);
            RedisConfig.getClient().lpush(QUEUE, payload);
            System.out.printf("Published scan result for scan %s to %s%n",
                result.scanId(), QUEUE);
        } catch (Exception e) {
            System.err.println("Failed to publish scan result: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
