package com.vulnwatch.worker.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Finding(
    @JsonProperty("scanId")      String scanId,
    @JsonProperty("surface")     String surface,   // Dns | Ssl | HttpHeaders
    @JsonProperty("severity")    String severity,  // Critical | High | Medium | Low
    @JsonProperty("title")       String title,
    @JsonProperty("cveId")       String cveId,
    @JsonProperty("aiExplanation")    String aiExplanation,
    @JsonProperty("technicalPayload") String technicalPayload,
    @JsonProperty("remediationSteps") String remediationSteps
) {}