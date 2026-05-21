package com.vulnwatch.worker;

import com.vulnwatch.worker.listener.QueueListener;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WorkerRunner implements ApplicationRunner {

    private final QueueListener queueListener;

    public WorkerRunner(QueueListener queueListener) {
        this.queueListener = queueListener;
    }

    @Override
    public void run(ApplicationArguments args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            queueListener.stop();
        }));

        queueListener.run(); // blocks — listens on queue
    }
}
