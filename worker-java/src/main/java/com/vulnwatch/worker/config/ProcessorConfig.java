package com.vulnwatch.worker.config;

import com.vulnwatch.worker.processor.JobProcessor;
import com.vulnwatch.worker.processor.RepositoryJobProcessor;
import com.vulnwatch.worker.processor.RetryableProcessor;
import com.vulnwatch.worker.processor.ScanJobProcessor;
import com.vulnwatch.worker.engine.repository.ScanEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Wires up the processor and scanner maps.
 *
 * ── Adding a new ecosystem scanner ───────────────────────────────────────────
 * 1. Implement DependencyScanner
 * 2. Annotate with @Component("manifest-filename") e.g. @Component("pom.xml")
 * Spring auto-collects all DependencyScanner beans into Map<String, DependencyScanner>
 * — no changes needed here.
 *
 * ── Adding a new job type ─────────────────────────────────────────────────────
 * 1. Implement JobProcessor
 * 2. Register a @Bean below with the job type name as the key
 */
@Configuration
public class ProcessorConfig {

    /**
     * Spring auto-collects all @Component DependencyScanner beans here,
     * keyed by their bean name (e.g. "package.json", "pom.xml").
     * Injected into RepositoryJobProcessor automatically.
     */

    @Bean("Domain")
    public JobProcessor domainProcessor(ScanJobProcessor scanJobProcessor) {
        return new RetryableProcessor(scanJobProcessor);
    }

    @Bean("Repository")
    public JobProcessor repositoryProcessor(RepositoryJobProcessor repositoryJobProcessor) {
        return new RetryableProcessor(repositoryJobProcessor);
    }
}