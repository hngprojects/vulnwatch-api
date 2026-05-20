package com.vulnwatch.worker.model;

import java.util.List;

/**
 * Structured JSON response from the Grok enrichment call.
 * The AI receives real engine outputs and returns analysis.
 */
public record AiResult(
    String severity,            // Critical | High | Medium | Low
    String explanation,         // natural-language summary for the user
    List<String> remediationSteps,
    String cveId                // nullable — only if a known CVE applies
) {}
