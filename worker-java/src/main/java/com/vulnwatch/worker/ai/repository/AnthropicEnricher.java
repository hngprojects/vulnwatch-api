package com.vulnwatch.worker.ai.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.DependencyFinding;
import com.vulnwatch.worker.model.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the dependency list to Claude (Anthropic API) for vulnerability analysis.
 *
 * Required env vars:
 *   ANTHROPIC_API_KEY
 *   AI_MODEL           — defaults to claude-sonnet-4-20250514
 *   AI_MAX_TOKENS      — defaults to 4096
 */
@Service
public class AnthropicEnricher {

    private static final Logger log = LoggerFactory.getLogger(AnthropicEnricher.class);
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public AnthropicEnricher(
            @Value("${anthropic.api-key}") String apiKey,
            @Value("${ai.model:claude-sonnet-4-20250514}") String model,
            @Value("${ai.max-tokens:4096}") int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.http = HttpClient.newBuilder()  
                .connectTimeout(java.time.Duration.ofSeconds(10))  
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Analyses a batch of "name@version" dependency strings.
     * Returns one DependencyFinding per input, preserving order.
     */
    public List<DependencyFinding> enrich(List<String> dependencies, ScanJob job) {
        if (dependencies.isEmpty()) return List.of();

        log.info("[{}] Sending {} dependencies to AI for enrichment", job.scanId(), dependencies.size());

        String prompt = buildPrompt(dependencies);

        try {
            String responseBody = callApi(prompt);
            return parseAiResponse(responseBody, dependencies);
        } catch (Exception e) {
            log.error("[{}] AI enrichment failed: {}", job.scanId(), e.getMessage(), e);
            // Fail open — return deps without enrichment rather than killing the scan
            return dependencies.stream()
                    .map(raw -> fallbackDependency(raw, "AI enrichment unavailable"))
                    .toList();
        }
    }

    // ── Prompt ───────────────────────────────────────────────────────────────

    private String buildPrompt(List<String> dependencies) {
        return """
            You are a security analyst. Analyse the following npm dependencies for known vulnerabilities.

            For each dependency return a JSON array where each element has exactly these fields:
            - "name": string
            - "version": string
            - "hasVulnerabilities": boolean
            - "severity": one of "CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE"
            - "cveIds": array of CVE ID strings (empty array if none)
            - "summary": one sentence plain-English description of the risk, or "No known vulnerabilities."
            - "recommendation": specific upgrade or mitigation advice, or "No action required."

            Return ONLY a valid JSON array. No markdown, no explanation, no code fences.

            Dependencies:
            %s
            """.formatted(String.join("\n", dependencies));
    }

    // ── API call ─────────────────────────────────────────────────────────────

    private String callApi(String prompt) throws Exception {
        String body = mapper.writeValueAsString(new AnthropicRequest(model, maxTokens, prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }

        // Extract the text content from the Anthropic response envelope
        JsonNode root = mapper.readTree(response.body());
        return root.at("/content/0/text").asText();
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private List<DependencyFinding> parseAiResponse(String rawJson, List<String> originalDeps) {
        try {
            JsonNode array = mapper.readTree(rawJson);
            List<DependencyFinding> results = new ArrayList<>();

            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                String raw = i < originalDeps.size() ? originalDeps.get(i) : node.get("name").asText();

                List<String> cveIds = new ArrayList<>();
                node.get("cveIds").forEach(cve -> cveIds.add(cve.asText()));

                results.add(new DependencyFinding(
                        node.get("name").asText(),
                        node.get("version").asText(),
                        raw,
                        node.get("hasVulnerabilities").asBoolean(),
                        node.get("severity").asText(),
                        cveIds,
                        node.get("summary").asText(),
                        node.get("recommendation").asText()
                ));
            }

            return results;
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", e.getMessage());
            return originalDeps.stream()
                    .map(raw -> fallbackDependency(raw, "Parse error"))
                    .toList();
        }
    }

    private DependencyFinding fallbackDependency(String raw, String reason) {
                int lastAt = raw.lastIndexOf('@');  
        String name;  
        String version;  
        if (lastAt > 0) {  
            name = raw.substring(0, lastAt);  
            version = raw.substring(lastAt + 1);  
        } else {  
            name = raw;  
            version = "unknown";  
        }  
        return new DependencyFinding(  
                name, version,  
                raw, false, "NONE", List.of(),  
                reason, "Retry scan or check manually."  
        ); 
    }

    // ── Request record ────────────────────────────────────────────────────────

    private record AnthropicRequest(String model, int max_tokens, String userPrompt) {
        // Custom serialization handled by Jackson field naming — see below
    }
}
