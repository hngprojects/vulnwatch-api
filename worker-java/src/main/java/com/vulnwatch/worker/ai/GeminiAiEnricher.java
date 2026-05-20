package com.vulnwatch.worker.ai;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.AiResult;
import com.vulnwatch.worker.model.ScanJob;
import com.vulnwatch.worker.model.payload.DnsPayload;
import com.vulnwatch.worker.model.payload.HttpPayload;
import com.vulnwatch.worker.model.payload.SslPayload;

import okhttp3.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GeminiAiEnricher {

    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .build();
    private final String apiKey = System.getenv("GEMINI_API_KEY");

    /**
     * Enriches a single engine result with AI severity analysis.
     * Returns null on failure — caller must handle gracefully.
     */
    public AiResult enrich(ScanJob job, EngineResult engineResult) {
        try {
            String prompt = buildPrompt(job, engineResult);

            Map<String, Object> body = Map.of(
                    "system_instruction", Map.of(
                            "parts", List.of(Map.of("text", systemPrompt()))),
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "maxOutputTokens", 800,
                            "responseMimeType", "application/json"));

            String url = API_URL + "?key=" + apiKey;

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(
                            mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();
                System.out.printf("[Gemini] %s/%s → HTTP %d%n",
                        job.scanId(), engineResult.surface(), response.code());

                if (!response.isSuccessful()) {
                    System.err.println("[Gemini] API error: " + responseBody);
                    return null;
                }

                String content = extractContent(responseBody);
                System.out.println("[Gemini] raw response: " + content);

                String json = stripFences(content);
                return mapper.readValue(json, AiResult.class);
            }
        } catch (Exception e) {
            System.err.printf("[Gemini] Enrichment failed for %s/%s: %s%n",
                    job.scanId(), engineResult.surface(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Generates a short scan-start description.
     */
    public String describe(ScanJob job) {
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", describePrompt(job))))),
                    "generationConfig", Map.of(
                            "maxOutputTokens", 200));

            String url = API_URL + "?key=" + apiKey;

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(
                            mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                return extractContent(response.body().string());
            }
        } catch (Exception e) {
            System.err.println("[Gemini] describe failed: " + e.getMessage());
            return null;
        }
    }

    // ── private helpers ────────────────────────────────────────────────────

    private String systemPrompt() {
        return """
                You are a cybersecurity analyst. You will receive raw technical scan data
                from DNS, SSL, or HTTP header probes. Your job is to analyse the data and
                return a structured JSON object with exactly these fields:
                  - severity: one of Critical, High, Medium, Low
                  - explanation: 2-3 sentence plain-English summary of what was found
                  - remediationSteps: array of short, actionable strings (3-5 items)
                  - cveId: a CVE identifier string if directly applicable, otherwise null
                Return ONLY the JSON object. No markdown, no fences, no preamble.
                """;
    }

    private String buildPrompt(ScanJob job, EngineResult result) {
        return String.format("""
                Domain: %s
                Scan ID: %s
                Surface: %s
                Engine success: %s
                Technical findings:
                %s

                Analyse these findings and return the JSON object described in the system prompt.
                """,
                job.domainName(),
                job.scanId(),
                result.surface(),
                result.success(),
                formatPayload(result));
    }

    private String formatPayload(EngineResult result) {
        if (!result.success())
            return "Engine failed: " + result.errorMessage();

        return switch (result.payload()) {
            case DnsPayload dns -> """
                    SPF: %s | DMARC: %s | MX: %s
                    Issues: %s
                    Raw records:
                    %s
                    """.formatted(
                    dns.hasSPF(), dns.hasDMARC(), dns.hasMX(),
                    dns.issues().isEmpty() ? "none" : String.join(", ", dns.issues()),
                    dns.rawRecords().entrySet().stream()
                            .map(e -> "  " + e.getKey() + ": " + e.getValue())
                            .collect(Collectors.joining("\n")));

            case SslPayload ssl -> """
                    Protocol: %s | Cipher: %s
                    Subject: %s | Expiry: %s (%d days)
                    Self-signed: %s | Expired: %s
                    Issues: %s
                    """.formatted(
                    ssl.protocol(), ssl.cipherSuite(),
                    ssl.certSubject(), ssl.certExpiry(), ssl.daysUntilExpiry(),
                    ssl.isSelfSigned(), ssl.isExpired(),
                    ssl.issues().isEmpty() ? "none" : String.join(", ", ssl.issues()));

            case HttpPayload http -> """
                    Status: %d | Server: %s
                    Present headers: %s
                    Missing headers: %s
                    Exposed technology: %s
                    Issues: %s
                    """.formatted(
                    http.statusCode(), http.serverHeader(),
                    http.presentHeaders().isEmpty() ? "none" : String.join(", ", http.presentHeaders()),
                    http.missingHeaders().isEmpty() ? "none" : String.join(", ", http.missingHeaders()),
                    http.exposedTechnology() != null ? http.exposedTechnology() : "none",
                    http.issues().isEmpty() ? "none" : String.join(", ", http.issues()));
        };
    }

    private String describePrompt(ScanJob job) {
        return String.format("""
                Generate a 2-3 sentence plain-English message telling a user that their
                security scan for domain "%s" (type: %s, scan ID: %s) has started.
                Mention what kinds of checks will run. Be professional and concise.
                Do not use bullet points.
                """,
                job.domainName(), job.scanType(), job.scanId());
    }

    private String extractContent(String responseBody) throws Exception {
        Map<?, ?> parsed = mapper.readValue(responseBody, Map.class);
        List<?> candidates = (List<?>) parsed.get("candidates");
        Map<?, ?> first = (Map<?, ?>) candidates.get(0);
        Map<?, ?> content = (Map<?, ?>) first.get("content");
        List<?> parts = (List<?>) content.get("parts");
        Map<?, ?> part = (Map<?, ?>) parts.get(0);
        return (String) part.get("text");
    }

    private String stripFences(String raw) {
        return raw.replaceAll("(?s)```json\\s*", "")
                .replaceAll("```", "")
                .trim();
    }
}
