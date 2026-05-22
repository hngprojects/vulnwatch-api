// package com.owlite.worker.listener;

// import java.util.Map;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;

// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.owlite.worker.config.AppConfig;
// import com.owlite.worker.processor.JobProcessor;

// public class ScanJobListener implements Runnable {
//     private final String queueName = AppConfig.get("redis.scan.job");
//     private final int blpopTimeout = AppConfig.getInt("worker.blpop.timeout");
//     private final Map<String, JobProcessor> processors;
//     private final ObjectMapper mapper = new ObjectMapper();
//     private final ExecutorService executor;
//     private volatile boolean running = true;

//      public ScanJobListener(Map<String, JobProcessor> processors) {
//         this.processors = processors;
//         this.executor = Executors.newVirtualThreadPerTaskExecutor();
//     }

// }
