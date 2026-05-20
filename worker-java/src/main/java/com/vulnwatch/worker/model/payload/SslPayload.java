package com.vulnwatch.worker.model.payload;

import java.util.List;

public record SslPayload(
    String protocol,
    String cipherSuite,
    String certSubject,
    String certExpiry,       // ISO-8601 string — easy for .NET to parse
    int daysUntilExpiry,
    boolean isSelfSigned,
    boolean isExpired,
    List<String> issues
) implements SurfacePayload {}
