// package com.owlite.worker.processor;

// import com.fasterxml.jackson.databind.MapperFeature;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.databind.json.JsonMapper;
// import com.owlite.worker.model.Finding;
// import com.owlite.worker.model.ScanJob;
// import okhttp3.*;

// import java.util.List;
// import java.util.Map;

// public class AiEnricher {

//     private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
//     private static final String FINDINGS_MODEL = "llama-3.3-70b-versatile";
//     private static final String DESCRIPTION_MODEL = "llama-3.1-8b-instant";
//     private final OkHttpClient http = new OkHttpClient();
//     private final String apiKey = System.getenv("GROQ_API_KEY");
//     private final ObjectMapper mapper = JsonMapper.builder()
//             .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
//             .build();

//     public List<Finding> generate(ScanJob job) {
//         try {
//             String prompt = buildGeneratorPrompt(job);

//             Map<String, Object> body = Map.of(
//                     "model", FINDINGS_MODEL,
//                     "max_tokens", 1500,
//                     "messages", List.of(
//                             Map.of("role", "user", "content", prompt)));

//             Request request = new Request.Builder()
//                     .url(API_URL)
//                     .post(RequestBody.create(
//                             mapper.writeValueAsString(body),
//                             MediaType.parse("application/json")))
//                     .header("Authorization", "Bearer " + apiKey)
//                     .header("Content-Type", "application/json")
//                     .build();

//             try (Response response = http.newCall(request).execute()) {
//                 String responseBody = response.body().string();
//                 System.out.printf("Groq findings response [%d]%n", response.code());

//                 if (!response.isSuccessful()) {
//                     System.err.println("Groq API error: " + response.code() + " " + responseBody);
//                     return List.of();
//                 }

//                 Map<?, ?> parsed = mapper.readValue(responseBody, Map.class);
//                 List<?> choices = (List<?>) parsed.get("choices");
//                 Map<?, ?> first = (Map<?, ?>) choices.get(0);
//                 Map<?, ?> message = (Map<?, ?>) first.get("message");
//                 String content = (String) message.get("content");

//                 System.out.println("Raw findings content: " + content); // log before parsing

//                 content = extractJsonArray(content);
//                 System.out.println("Cleaned findings content: " + content);

//                 Finding[] findings = mapper.readValue(content, Finding[].class);
//                 return List.of(findings);
//             }
//         } catch (Exception e) {
//             System.err.println("Failed to generate findings: " + e.getMessage());
//             e.printStackTrace();
//             return List.of();
//         }
//     }

//     private String extractJsonArray(String raw) {
//         raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

//         int start = raw.indexOf('[');
//         if (start == -1)
//             throw new IllegalArgumentException("No JSON array found in response");

//         raw = raw.substring(start);

//         int end = raw.lastIndexOf(']');

//         if (end == -1) {
//             // model cut off — repair
//             raw = raw.stripTrailing();

//             if (raw.endsWith(","))
//                 raw = raw.substring(0, raw.length() - 1);

//             if (!raw.endsWith("}"))
//                 raw = raw + "}";

//             raw = raw + "]";

//             System.out.println("Warning: repaired unclosed JSON array from model response");
//         } else {
//             raw = raw.substring(0, end + 1);
//         }

//         return raw;
//     }

//     public String describe(ScanJob job) {
//         try {
//             Map<String, Object> body = Map.of(
//                     "model", DESCRIPTION_MODEL,
//                     "max_tokens", 200,
//                     "messages", List.of(
//                             Map.of("role", "user", "content", buildPrompt(job))));

//             Request request = new Request.Builder()
//                     .url(API_URL)
//                     .post(RequestBody.create(
//                             mapper.writeValueAsString(body),
//                             MediaType.parse("application/json")))
//                     .header("Authorization", "Bearer " + apiKey)
//                     .header("Content-Type", "application/json")
//                     .build();

//             try (Response response = http.newCall(request).execute()) {
//                 String responseBody = response.body().string();
//                 System.out.printf("Groq response [%d]: %s%n", response.code(), responseBody);

//                 if (!response.isSuccessful()) {
//                     System.err.println("Groq API error: " + response.code());
//                     return null;
//                 }

//                 Map<?, ?> parsed = mapper.readValue(responseBody, Map.class);
//                 List<?> choices = (List<?>) parsed.get("choices");
//                 Map<?, ?> first = (Map<?, ?>) choices.get(0);
//                 Map<?, ?> message = (Map<?, ?>) first.get("message");
//                 return (String) message.get("content");
//             }
//         } catch (Exception e) {
//             System.err.println("Failed to generate scan description: " + e.getMessage());
//             e.printStackTrace();
//             return null;
//         }
//     }

//     private String buildGeneratorPrompt(ScanJob job) {
//         return String.format(
//                 """
//                         Generate exactly 4 fictional vulnerability findings for domain "%s" as a JSON array.

//                         CRITICAL RULES:
//                         - Return ONLY the JSON array. No text before or after it. No markdown. No code fences.
//                         - Do NOT use apostrophes or contractions in any string value (write "does not" not "doesn't")
//                         - surface must be exactly one of: Dns, Ssl, HttpHeaders
//                         - severity must be exactly one of: Critical, High, Medium, Low
//                         - include at least one finding for each surface type
//                         - cveId must be null or a string like "CVE-2023-1234"
//                         - scanId must be "%s" for every finding

//                         Required JSON shape (return an array of exactly this structure):
//                         [{"scanId":"%s","surface":"Ssl","severity":"High","title":"...","cveId":null,"aiExplanation":"...","technicalPayload":"...","remediationSteps":"..."}]
//                         """,
//                 job.domainName(),
//                 job.scanId(),
//                 job.scanId());
//     }

//     private String buildPrompt(ScanJob job) {
//         return String.format(
//                 """
//                         You are a cybersecurity assistant helping a user understand a vulnerability scan they just initiated.

//                         Generate a short but informative response (2–4 sentences) that:
//                         - sounds natural and professional
//                         - explains what the scan is doing in plain English
//                         - briefly mentions what kinds of issues may be checked
//                         - reassures the user that results will be available after analysis
//                         - avoids heavy technical jargon
//                         - does NOT invent findings or vulnerabilities
//                         - does NOT use bullet points

//                         Scan Details:
//                         - Domain: %s
//                         - Scan type: %s
//                         - Requested at: %s
//                         - Scan ID: %s

//                         The response should feel intelligent, helpful, and conversational, like a modern AI security platform.

//                         Example style:
//                         "Your security scan for tonydim.site is now underway. The system will analyze the domain for potential weaknesses, exposed services, and common security risks associated with the selected scan type. Once the analysis is complete, you'll receive a detailed breakdown of any findings and recommended next steps."

//                         Generate the response now.
//                         """,
//                 job.domainName(),
//                 job.scanType(),
//                 job.enqueuedAt(),
//                 job.scanId());
//     }
// }