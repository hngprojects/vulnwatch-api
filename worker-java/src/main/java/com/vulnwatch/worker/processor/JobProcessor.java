package com.vulnwatch.worker.processor;

import com.vulnwatch.worker.model.ScanJob;

public interface JobProcessor {
    void process(ScanJob job);
}