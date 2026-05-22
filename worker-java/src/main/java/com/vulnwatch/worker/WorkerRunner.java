package com.vulnwatch.worker;

import com.vulnwatch.worker.listener.QueueListener;

import org.slf4j.Logger;  
import org.slf4j.LoggerFactory; 
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WorkerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);
    private final QueueListener queueListener;

    public WorkerRunner(QueueListener queueListener) {
        this.queueListener = queueListener;
    }

    @Override
    public void run(ApplicationArguments args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            queueListener.stop();
        }));

        queueListener.run(); // blocks — listens on queue
    }
}

