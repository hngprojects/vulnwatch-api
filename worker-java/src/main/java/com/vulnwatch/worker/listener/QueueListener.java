package com.vulnwatch.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.processor.JobProcessor;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QueueListener implements Runnable {
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
                if (result == null) continue;
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
        System.out.println("Received job payload: " + raw);
        try {
            ScanJob job = mapper.readValue(raw, ScanJob.class);
            System.out.printf("Parsed job: scanId=%s domainId=%s scanType=%s%n",
                    job.scanId(), job.domainId(), job.scanType());

            JobProcessor processor = processors.get(job.scanType());
            if (processor == null) {
                System.err.printf("No processor for job type: '%s'. Registered types: %s%n",
                        job.scanType(), processors.keySet());
                return;
            }
            processor.process(job);
        } catch (Exception e) {
            System.err.println("Failed to process job: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }
}