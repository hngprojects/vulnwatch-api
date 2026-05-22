package com.vulnwatch.worker.config;

import com.vulnwatch.worker.processor.DomainJobProcessor;
import com.vulnwatch.worker.processor.JobProcessor;
import com.vulnwatch.worker.processor.RepositoryJobProcessor;
import com.vulnwatch.worker.processor.RetryableProcessor;
import com.vulnwatch.worker.publisher.DomainIntelPublisher;
import com.vulnwatch.worker.publisher.RepositoryIntelPublisher;
import com.vulnwatch.worker.service.GithubService;

import redis.clients.jedis.JedisPooled;

import com.vulnwatch.worker.ai.GroqAiEnricher;
import com.vulnwatch.worker.ai.repository.AnthropicEnricher;
import com.vulnwatch.worker.engine.ParallelScanner;
import com.vulnwatch.worker.engine.repository.ScanEngine;
import com.vulnwatch.worker.persistence.DomainPersistence;
import com.vulnwatch.worker.persistence.RepositoryPersistence;

import org.springframework.beans.factory.annotation.Value;
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

    @Bean("Repository")
    public JobProcessor repositoryOrchestrator(
            GithubService githubService,
            Map<String, ScanEngine> scanners,
            AnthropicEnricher aiEnrichmentService,
            RepositoryPersistence repo,
            RepositoryIntelPublisher redisPublisher,
            JedisPooled jedisPooled,
            @Value("${worker.dlq.key:dead-letter}") String dlqKey) {
        return new RetryableProcessor(
            new RepositoryJobProcessor(
                githubService,
                scanners,
                aiEnrichmentService,
                repo,
                redisPublisher
            ),
            jedisPooled,
            dlqKey
        );
    }

    @Bean("Domain")
    public JobProcessor domainOrchestrator(
            ParallelScanner scanner,
            GroqAiEnricher enricher,
            DomainPersistence repo,
            DomainIntelPublisher publisher,
            JedisPooled jedisPooled,
            @Value("${worker.scanresult.queue:scan-results}") String scanResultQueue,
            @Value("${worker.dlq.key:dead-letter}") String dlqKey) {
        return new RetryableProcessor(
            new DomainJobProcessor(
                scanner,
                enricher,
                repo,
                publisher
            ),
            jedisPooled,
            dlqKey
        );
    }
    
}