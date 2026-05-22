package com.vulnwatch.worker.model;

import java.util.List;

/**
 * A dependency after AI analysis.
 * "raw" is the original "name@version" string from the manifest.
 */
public record DependencyFinding(
        String name,
        String version,
        String raw,
        boolean hasVulnerabilities,
        String severity,            // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE"
        List<String> cveIds,
        String summary,             // AI-generated plain-English explanation
        String recommendation        // AI-generated fix/upgrade advice
) {
    public boolean hasVulnerabilities() {
        return hasVulnerabilities;
    }
}

