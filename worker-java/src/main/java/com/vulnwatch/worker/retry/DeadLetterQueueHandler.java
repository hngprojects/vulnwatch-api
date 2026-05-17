package com.vulnwatch.worker.retry;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeadLetterQueueHandler {

    private final RedisTemplate<Object, Object> redisTemplate;

    private static final String DLQ_KEY = "retry:scan:queue";

    public void pushToDLQ(ScanTask task){
        redisTemplate.opsForList()
                .leftPush(DLQ_KEY, task);
    }

}
