package com.vulnwatch.worker.model.payload;

import java.util.List;
import java.util.Map;

public record DnsPayload(
    boolean hasSPF,
    boolean hasDMARC,
    boolean hasMX,
    List<String> issues,
    Map<String, String> rawRecords
) implements SurfacePayload {}