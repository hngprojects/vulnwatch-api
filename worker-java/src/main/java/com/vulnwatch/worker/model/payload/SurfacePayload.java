package com.vulnwatch.worker.model.payload;

public sealed interface SurfacePayload
    permits DnsPayload, SslPayload, HttpPayload {}