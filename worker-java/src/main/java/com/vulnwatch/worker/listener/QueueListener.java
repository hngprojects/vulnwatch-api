package com.vulnwatch.worker.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.AppConfig;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.processor.JobProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueueListener implements Runnable {
    private final String queueName = AppConfig.get("redis.queue");
    private final int blpopTimeout = AppConfig.getInt("worker.blpop.timeout");
    private final Map<String, JobProcessor> processors;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;
    private volatile boolean running = true;

    public QueueListener(Map<String, JobProcessor> processors) {
        this.processors = processors;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void run() {
        System.out.println("Listeninng on queue: " + queueName);
        while (running) {
            try {
                // JedisPooled manages the pool internally — no try-with-resource needed
                List<String> result = RedisConfig.getClient().blpop(blpopTimeout, queueName);
                if (result == null)
                    continue;
                String payload = result.get(1);
                executor.submit(() -> handle(payload));
            } catch (Exception e) {
                System.err.println("Listener error: " + e.getMessage());
                // brief pause before retrying to avoid tight error loops
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handle(String raw) {
        System.out.println("Received job payload: " + raw); // log raw before parsing
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
            e.printStackTrace(); // full stack trace instead of just the message
        }
    }

    public void stop() {
        running = false;
        executor.shutdown();
    }
}