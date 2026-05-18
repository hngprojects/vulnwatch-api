package com.vulnwatch.worker.retry;

import com.vulnwatch.worker.SurfaceResultEvent;
import com.vulnwatch.worker.enums.SurfaceType;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RetryPolicyFactory {

    private final Map<SurfaceType, RetryPolicy> policies = Map.of(
            SurfaceType.DNS, new RetryPolicy(5, 10000),
            SurfaceType.SSL, new RetryPolicy(3, 15000),
            SurfaceType.HTTP_HEADERS, new RetryPolicy(2, 5000)
    );

    public RetryPolicy getPolicy(SurfaceType surface) {
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
