package com.vulnwatch.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.ScanJob;
import redis.clients.jedis.JedisPooled;

public class RetryableProcessor implements JobProcessor {

    private static final int  MAX_ATTEMPTS  = 3;
    private static final long BASE_DELAY_MS = 2_000L;

    private final JobProcessor delegate;
    private final JedisPooled  jedis;
    private final String       dlqKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public RetryableProcessor(JobProcessor delegate, JedisPooled jedisPooled, String dlqKey) {
        this.delegate = delegate;
        this.jedis    = jedisPooled;
        this.dlqKey   = dlqKey;
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
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastException = e;
                System.err.printf("[Retry] %s → attempt %d failed: %s%n",
                    job.scanId(), attempt, e.getMessage());
            }
        }

        pushToDlq(job, lastException);
    }

    private void pushToDlq(ScanJob job, Exception cause) {
        try {
            String payload = mapper.writeValueAsString(job);
            jedis.lpush(dlqKey, payload);
            System.err.printf("[DLQ] %s → pushed after %d failed attempts. Reason: %s%n",
                job.scanId(), MAX_ATTEMPTS, cause != null ? cause.getMessage() : "unknown");
        } catch (Exception e) {
            System.err.println("[DLQ] Failed to push to DLQ: " + e.getMessage());
            e.printStackTrace();
        }
    }
}