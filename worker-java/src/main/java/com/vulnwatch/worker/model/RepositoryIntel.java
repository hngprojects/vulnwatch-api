package com.vulnwatch.worker.model;

import com.vulnwatch.worker.model.ScanJob;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RepositoryIntel(
        String scanId,
        String repoId,
        String requestedBy,
        List<String> ecosystems,           // "npm", "maven", etc.
        // List<DependencyFinding> dependencies,
        Map<String, List<DependencyFinding>> byEcosystem,
        int totalDependencies,
        int vulnerableCount,
        String overallSeverity,     // highest severity found
        Instant completedAt
) {
    public static RepositoryIntel of(
            ScanJob job,
            Map<String, List<DependencyFinding>> enrichedByEcosystem) {
 
        List<DependencyFinding> all = enrichedByEcosystem.values().stream()
                .flatMap(List::stream)
                .toList();
 
        int vulnerableCount = (int) all.stream()
                .filter(DependencyFinding::hasVulnerabilities)
                .count();
 
        String overallSeverity = all.stream()
                .filter(DependencyFinding::hasVulnerabilities)
                .map(DependencyFinding::severity)
                .reduce(RepositoryIntel::highestSeverity)
                .orElse("NONE");
 
        return new RepositoryIntel(
                job.scanId(),
                job.repoId(),
                job.requestedBy(),
                List.copyOf(enrichedByEcosystem.keySet()),
                enrichedByEcosystem,
                all.size(),
                vulnerableCount,
                overallSeverity,
                Instant.now()
        );
    }
 
    private static String highestSeverity(String a, String b) {
        List<String> order = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }
}