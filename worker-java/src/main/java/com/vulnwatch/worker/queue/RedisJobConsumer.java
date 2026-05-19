package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.processors.ScanProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJobConsumer {

  private final RedisTemplate<String, Object> redisTemplate;
  private final ObjectMapper objectMapper;
  private final ScanProcessor scanProcessor;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean running = new AtomicBoolean(true);

  @PostConstruct
  public void start() {
    executor.submit(this::consumeLoop);
    log.info("Redis BLPOP consumer started on queue: {}", RedisConfig.Keys.SCAN_QUEUE);
  }

  private void consumeLoop() {
    while (running.get()) {
      try {

        Object jobJson = redisTemplate.opsForList()
                .leftPop(RedisConfig.Keys.SCAN_QUEUE, 5, TimeUnit.SECONDS);

        if (jobJson == null) {
          continue; // Timeout or no message
        }

        log.debug("Received job: {}", jobJson);
        ScanJob job = objectMapper.readValue(jobJson.toString(), ScanJob.class);
        scanProcessor.process(job);

      } catch (Exception e) {
        log.error("Error consuming Redis job", e);
      }
    }
  }

  @PreDestroy
  public void stop() {
    log.info("Stopping Redis BLPOP consumer...");
    running.set(false);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}