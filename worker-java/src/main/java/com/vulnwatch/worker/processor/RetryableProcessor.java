package com.vulnwatch.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.model.ScanJob;

public class RetryableProcessor implements JobProcessor {

    private static final int    MAX_ATTEMPTS   = 3;
    private static final long   BASE_DELAY_MS  = 2_000L; // 2s → 4s → 8s
    private static final String DLQ_KEY        = "dead-letter";

    private final JobProcessor   delegate;
    private final ObjectMapper   mapper = new ObjectMapper();

    public RetryableProcessor(JobProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void process(ScanJob job) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    long delay = BASE_DELAY_MS * (long) Math.pow(2, attempt - 2);
                    System.out.printf("[Retry] %s → attempt %d/%d after %dms%n",
                        job.scanId(), attempt, MAX_ATTEMPTS, delay);
                    Thread.sleep(delay);
                }
                delegate.process(job);
                return; // success — done
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastException = e;
                System.err.printf("[Retry] %s → attempt %d failed: %s%n",
                    job.scanId(), attempt, e.getMessage());
            }
        }

        // All attempts exhausted — send to DLQ
        pushToDlq(job, lastException);
    }

    private void pushToDlq(ScanJob job, Exception cause) {
        try {
            String payload = mapper.writeValueAsString(job);
            RedisConfig.getClient().lpush(DLQ_KEY, payload);
            System.err.printf("[DLQ] %s → pushed after %d failed attempts. Reason: %s%n",
                job.scanId(), MAX_ATTEMPTS, cause != null ? cause.getMessage() : "unknown");
        } catch (Exception e) {
            System.err.println("[DLQ] Failed to push to DLQ: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
