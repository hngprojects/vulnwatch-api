package com.vulnwatch.worker.retry;

import com.vulnwatch.worker.SurfaceResultEvent;
import com.vulnwatch.worker.state.RedisSurfaceStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RetryHandler {

    private final RedisTemplate<Object, Object> redisTemplate;
    private final RetryPolicyFactory retryPolicyFactory;
    private final RedisSurfaceStateManager stateManager;
    private final DeadLetterQueueHandler deadLetterQueueHandler;

    private static final String RETRY_KEY = "scan:retry";

    public void handleFailure(SurfaceResultEvent event) {

        RetryPolicyFactory.RetryPolicy policy =
                retryPolicyFactory.getPolicy(event.getSurface());

        int nextRetry = stateManager.incrementRetryCount(event.getScanId(), event.getSurface());

        // max retry check
        if (nextRetry > policy.getMaxRetries()) {
            deadLetterQueueHandler.pushToDLQ(event);
            return;
        }

        long delay = policy.getBaseDelayMs() * (long) Math.pow(2, nextRetry - 1);
        long score = Instant.now().toEpochMilli() + delay;

        // store JSON in ZSET
        redisTemplate.opsForZSet().add(
                RETRY_KEY,
                event,
                score
        );
    }
}
