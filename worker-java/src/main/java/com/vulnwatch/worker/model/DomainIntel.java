package com.vulnwatch.worker.model;

import java.time.Instant;

public record DomainIntel(
        String scanId,
        String domainId,
        String domainName,
        String requestedBy,
        int securityScore,     // highest severity found
        Instant completedAt
) {
    public static DomainIntel of(
        ScanJob job,
        int securityScore
    ) {
        return new DomainIntel(
                job.scanId(),
                job.domainId(),
                job.domainName(),
                job.requestedBy(),
                securityScore,
                Instant.now()
        );
    }
}