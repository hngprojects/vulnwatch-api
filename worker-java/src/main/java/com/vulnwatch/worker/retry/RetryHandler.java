package com.vulnwatch.worker.retry;

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

    private static final String RETRY_KEY = "scan:retry";
    private static final String DLQ_KEY = "dlq:scan";

    public void handleFailure(ScanTask task) {

        RetryPolicyFactory.RetryPolicy policy =
                retryPolicyFactory.getPolicy(task.getSurface());

        int nextRetry = task.getRetryCount() + 1;

        // max retry check
        if (nextRetry > policy.getMaxRetries()) {
            moveToDLQ(task);
            return;
        }


        long delay = policy.getBaseDelayMs() * (long) Math.pow(2, nextRetry - 1);
        long score = Instant.now().toEpochMilli() + delay;

        // store JSON in ZSET
        redisTemplate.opsForZSet().add(
                RETRY_KEY,
                efdffd,
                score
        );
    }

    private void moveToDLQ(ScanTask task) {
        redisTemplate.opsForList()
                .leftPush(DLQ_KEY, jsonUtil.toJson(task));
    }
}
