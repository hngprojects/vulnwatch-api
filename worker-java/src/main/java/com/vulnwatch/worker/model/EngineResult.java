package com.vulnwatch.worker.model;

import com.vulnwatch.worker.model.payload.SurfacePayload;

/**
 * Raw output from a single scan engine before AI enrichment.
 * technicalData is a typed payload of whatever the
 * engine discovered.
 */
public record EngineResult(
    String surface,          // Dns | Ssl | HttpHeaders
    boolean success,
    String errorMessage,     // null when success=true
    SurfacePayload payload
) {
    public static EngineResult failure(String surface, String reason) {
        return new EngineResult(surface, false, reason, null);
    }
}
