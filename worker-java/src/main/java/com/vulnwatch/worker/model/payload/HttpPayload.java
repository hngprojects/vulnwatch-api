package com.vulnwatch.worker.model.payload;

import java.util.List;

public record HttpPayload(
    int statusCode,
    String serverHeader,
    List<String> presentHeaders,
    List<String> missingHeaders,
    String exposedTechnology, // nullable
    List<String> issues
) implements SurfacePayload {}

