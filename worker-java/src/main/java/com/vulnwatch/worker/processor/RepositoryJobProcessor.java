package com.vulnwatch.worker.processor;

import com.vulnwatch.worker.model.ScanJob;

public class RepositoryJobProcessor implements JobProcessor {
    @Override
    public void process(ScanJob job) {
        String to = (String) job.domainName();
        System.out.printf("[RepositoryJob] %s → sending email to %s%n", job.scanId(), to);
        // plug in your actual email logic here
    }
}