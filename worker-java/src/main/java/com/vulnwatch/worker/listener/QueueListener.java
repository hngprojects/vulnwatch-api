package com.vulnwatch.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.ai.repository.AnthropicEnricher;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.processor.JobProcessor;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QueueListener implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(QueueListener.class);
    private final String queueName;
    private final int blpopTimeout;
    private final Map<String, JobProcessor> processors;
    private final JedisPooled jedis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;
    private volatile boolean running = true;

    public QueueListener(
            JedisPooled jedisPooled,
            Map<String, JobProcessor> processors,
            @Value("${worker.blpop.timeout:5}") int blpopTimeout,
            @Value("${worker.scanjob.queue:scan-jobs}") String queueName) {
        this.jedis = jedisPooled;
        this.processors = processors;
        this.blpopTimeout = blpopTimeout;
        this.queueName = queueName;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void run() {
        System.out.println("Listening on queue: " + queueName);
        while (running) {
            try {
                List<String> result = jedis.blpop(blpopTimeout, queueName);
                if (result == null)
                    continue;
                String payload = result.get(1);
                executor.submit(() -> handle(payload));
            } catch (Exception e) {
                System.err.println("Listener error: " + e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handle(String raw) {
        ScanJob job;
        try {
            job = mapper.readValue(raw, ScanJob.class);
        } catch (Exception e) {
            log.error("Failed to deserialize job payload: {}", raw, e);
            return;
        }

        try {
            log.info("Parsed job: scanId={} domainId={} scanType={}",
                    job.scanId(), job.domainId(), job.scanType());

            JobProcessor processor = processors.get(job.scanType());
            if (processor == null) {
                log.warn("No processor for job type: '{}'. Registered types: {}",
                        job.scanType(), processors.keySet());
                return;
            }
            processor.process(job);
        } catch (Exception e) {
            log.error("Failed to process scan; scanId={}, scanType={}", job.scanId(), job.scanType(), e);
        }
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }
}