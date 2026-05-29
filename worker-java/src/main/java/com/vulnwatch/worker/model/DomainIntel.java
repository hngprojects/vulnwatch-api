package com.vulnwatch.worker.model;

import com.vulnwatch.worker.enums.AiAvailability;

import java.time.Instant;

public record DomainIntel(
        String scanId,
        String domainId,
        String domainName,
        String requestedBy,
        int securityScore,
        AiAvailability aiAvailability,
        Instant completedAt
) {
    public static DomainIntel of(ScanJob job, int securityScore, AiAvailability aiAvailability) {
        return new DomainIntel(
                job.scanId(),
                job.domainId(),
                job.domainName(),
                job.requestedBy(),
                securityScore,
                aiAvailability,
                Instant.now()
        );
    }
}