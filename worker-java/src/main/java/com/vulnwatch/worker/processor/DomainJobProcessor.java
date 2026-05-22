package com.vulnwatch.worker.processor;

import java.util.ArrayList;
import java.util.List;

import com.vulnwatch.worker.ai.GroqAiEnricher;
import com.vulnwatch.worker.engine.ParallelScanner;
import com.vulnwatch.worker.model.AiResult;
import com.vulnwatch.worker.model.DomainFinding;
import com.vulnwatch.worker.model.DomainIntel;
import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.persistence.DomainPersistence;
import com.vulnwatch.worker.publisher.DomainIntelPublisher;

public class DomainJobProcessor implements JobProcessor {
    
    private final ParallelScanner           scanner;
    private final GroqAiEnricher            enricher;
    private final DomainPersistence         persistence;
    private final DomainIntelPublisher       publisher;

    public DomainJobProcessor(
            ParallelScanner scanner,
            GroqAiEnricher enricher,
            DomainPersistence persistence,
            DomainIntelPublisher publisher) {
        this.scanner     = scanner;
        this.enricher    = enricher;
        this.persistence = persistence;
        this.publisher   = publisher;
    }

    @Override
    public void process(ScanJob job) {
                System.out.printf("[ScanJob] %s → starting for %s%n", job.scanId(), job.domainName());

        // Step 1 — generate a friendly description (non-blocking, best-effort)
        String description = enricher.describe(job);
        if (description != null) {
            System.out.printf("[ScanJob] %s → %s%n", job.scanId(), description);
        }

        // Step 2 — run DNS, SSL, and HTTP engines in parallel
        List<EngineResult> engineResults;
        try {
            engineResults = scanner.scan(job);
        } catch (Exception e) {
            System.err.printf("[ScanJob] %s → parallel scan failed: %s%n",
                job.scanId(), e.getMessage());
            throw new RuntimeException("Parallel scan failed", e); // triggers retry
        }

        // Step 3 — enrich each engine result via Grok (sequential — rate limit safe)
        List<AiResult> enrichments = new ArrayList<>();
        for (EngineResult result : engineResults) {
            AiResult enrichment = enricher.enrich(job, result);
            enrichments.add(enrichment); // null entries handled gracefully in persistence
        }

        // Step 4 — compute security score from enrichment severities
        int score = computeScore(enrichments);
        System.out.printf("[ScanJob] %s → security score: %d%n", job.scanId(), score);

        // Step 5 — persist findings to PostgreSQL (assembles Finding records internally)
        List<DomainFinding> findings = persistence.saveFindings(
            job.scanId(), engineResults, enrichments, score);

        if (findings.isEmpty()) {
            System.err.println("[ScanJob] No findings persisted for " + job.scanId());
            return;
        }

        // Step 6 — publish finished signal to Redis for .NET to consume
        DomainIntel result = DomainIntel.of(job, score);
        publisher.publishSuccess(job, result);

        System.out.printf("[ScanJob] %s → completed%n", job.scanId());
    }

    private int computeScore(List<AiResult> enrichments) {
           int deductions = enrichments.stream()
            .filter(e -> e != null)
            .mapToInt(e -> switch (e.severity()) {
                case "Critical" -> 30;
                case "High"     -> 20;
                case "Medium"   -> 10;
                case "Low"      ->  5;
                default         ->  0;
            })
            .sum();
        return Math.max(0, 100 - deductions);
    }

}



