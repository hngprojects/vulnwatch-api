package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.processors.ScanProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisJobConsumer implements MessageListener {

  private final ObjectMapper objectMapper;
  private final ScanProcessor scanProcessor;

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      ScanJob job = objectMapper.readValue(message.getBody(), ScanJob.class);
      scanProcessor.process(job);
    } catch (Exception e) {
      System.err.println("Failed to process scan job: " + e.getMessage());
    }
  }
}
