package com.vulnwatch.worker.model;

import java.util.List;

public record ScanResult(
    String scanId,
    String domainId,
    String domainName,
    String requestedBy,
    int securityScore,
    List<Finding> findings
) {}