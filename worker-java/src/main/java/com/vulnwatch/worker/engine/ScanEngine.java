package com.vulnwatch.worker.engine;

import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.ScanJob;

public interface ScanEngine {
    /** Surface identifier — must match Finding.surface values: Dns | Ssl | HttpHeaders */
    String surface();
    EngineResult run(ScanJob job);
}

