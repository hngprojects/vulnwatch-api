package com.vulnwatch.worker.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ScanResult;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilder {

  private final ObjectMapper objectMapper;

  public String buildPrompt(AggregatedScanData aggregatedData) {
    String scanDataJson = convertScanDataToJson(aggregatedData);
    return String.format(PROMPT_TEMPLATE, scanDataJson);
  }

  public String convertScanDataToJson(AggregatedScanData aggregatedData) {

    Map<String, Object> scanResults = new LinkedHashMap<>();

    if (aggregatedData.getScanJob() != null && aggregatedData.getScanJob().getDomain() != null) {
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("domain", aggregatedData.getScanJob().getDomain());
      metadata.put("scan_id", aggregatedData.getScanId().toString());
      scanResults.put("_metadata", metadata);
    }

    for (ScanResult result : aggregatedData.getSuccessfulResults()) {
      String key = result.getSurface().name().toLowerCase();
      Map<String, Object> rawData = result.getRawData();

      if (rawData == null) {
        log.warn("Null rawData for scanner: {}, using empty map", result.getScannerName());
        rawData = new LinkedHashMap<>();
      }
      scanResults.put(key, rawData);
    }

    try {
      return objectMapper.writeValueAsString(scanResults);
    } catch (Exception e) {
      log.error("Failed to convert scan data to JSON", e);
      return "{}";
    }
  }

  private static final String PROMPT_TEMPLATE =
      """
            You are VulnWatch AI, a world-class security expert for developers and business owners.

            ## YOUR ROLE
            Act as a senior security analyst. Translate technical scan results into clear,
            actionable security intelligence.

            ## CRITICAL: ENUM VALUE REQUIREMENTS
            The 'severity' field MUST be exactly one of: Critical, High, Medium, Low
            The 'surface' field MUST be exactly one of: DNS, SSL, HTTP_HEADERS, CT_LOG, EXPOSURE, DEPENDENCY, SAST, SECRETS

            ## SCORING GUIDELINES
            - Critical (100-80): Take immediate action - exposed secrets, exploitable vulnerabilities
            - High (79-60): Address soon - missing critical security headers, expiring certificates
            - Medium (59-40): Plan to fix - missing security best practices
            - Low (39-0): Informational - minor improvements

            ## SCAN DATA TO ANALYZE
            %s

            ## RESPONSE REQUIREMENTS
            Return findings as a JSON object matching the specified schema.
            Do not include any text outside the JSON response.
            """;
}
