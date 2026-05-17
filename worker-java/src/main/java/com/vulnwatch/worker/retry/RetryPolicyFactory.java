package com.vulnwatch.worker.retry;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RetryPolicyFactory {

    private final Map<String, RetryPolicy> policies = Map.of(
            "DNS", new RetryPolicy(5, 10000),
            "SSL", new RetryPolicy(3, 15000),
            "HEADERS", new RetryPolicy(2, 5000)
    );

    public RetryPolicy getPolicy(String surface) {
        return policies.getOrDefault(surface,
                new RetryPolicy(3, 10000));
    }

    public static class RetryPolicy {
        private final int maxRetries;
        private final long baseDelayMs;

        public RetryPolicy(int maxRetries, long baseDelayMs) {
            this.maxRetries = maxRetries;
            this.baseDelayMs = baseDelayMs;
        }

        public int getMaxRetries() { return maxRetries; }
        public long getBaseDelayMs() { return baseDelayMs; }
    }
}
