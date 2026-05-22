package com.vulnwatch.worker.engine;

import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.ScanJob;

import java.util.List;
import java.util.concurrent.StructuredTaskScope;

import org.springframework.stereotype.Component;

@Component
public class ParallelScanner {

    private final List<ScanEngine> engines;

    public ParallelScanner() {
        this.engines = List.of(new DnsEngine(), new SslEngine(), new HttpEngine());
    }

    /**
     * Runs all three engines in parallel using Java 21 virtual threads.
     * Uses ShutdownOnFailure — if any engine throws an unhandled exception
     * the scope is cancelled and the exception is re-thrown. Engine-level
     * errors (connection refused, timeout) are caught inside each engine and
     * returned as EngineResult.failure(...) so the scan continues.
     */
    @SuppressWarnings("preview")
    public List<EngineResult> scan(ScanJob job) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            List<StructuredTaskScope.Subtask<EngineResult>> subtasks = engines.stream()
                .map(engine -> scope.fork(() -> {
                    System.out.printf("[%s] Starting %s engine for %s%n",
                        job.scanId(), engine.surface(), job.domainName());
                    EngineResult result = engine.run(job);
                    System.out.printf("[%s] %s engine finished — success=%b%n",
                        job.scanId(), engine.surface(), result.success());
                    return result;
                }))
                .toList();

            scope.join();           // wait for all three
            scope.throwIfFailed();  // re-throw if any threw (not expected — engines catch internally)

            return subtasks.stream()
                .map(StructuredTaskScope.Subtask::get)
                .toList();
        }
    }
}
