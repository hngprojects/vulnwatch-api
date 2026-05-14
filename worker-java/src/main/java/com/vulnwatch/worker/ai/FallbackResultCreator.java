package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.FindingStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Creates fallback results when AI enrichment fails. Ensures scans can complete even when OpenAI is
 * unavailable.
 *
 * <p>This component has NO external dependencies (no Redis, no Database, no HTTP). It is purely a
 * data creator, making it easily unit testable.
 */
@Slf4j
@Component
public class FallbackResultCreator {

  private static final int FALLBACK_SECURITY_SCORE = 50;

  /**
   * Creates a fallback enriched result when AI enrichment fails.
   *
   * @param scanId The ID of the scan
   * @param errorMessage The error message from the AI failure
   * @return Fallback EnrichedScanResult with neutral score and explanatory finding
   */
  public EnrichedScanResult create(UUID scanId, String errorMessage) {
    log.warn("Creating fallback result for scan: {} due to error: {}", scanId, errorMessage);

    Finding fallbackFinding = createFallbackFinding(scanId, errorMessage);

    return EnrichedScanResult.builder()
        .scanId(scanId)
        .securityScore(FALLBACK_SECURITY_SCORE)
        .findings(List.of(fallbackFinding))
        .processedAt(Instant.now())
        //                .scoreMethod("fallback")
        .build();
  }

  /**
   * Creates a fallback finding explaining that AI analysis is unavailable.
   *
   * @param scanId The ID of the scan
   * @param errorMessage The error message from the AI failure
   * @return Fallback Finding entity
   */
  private Finding createFallbackFinding(UUID scanId, String errorMessage) {
    return Finding.builder()
        .id(UUID.randomUUID())
        .scanId(scanId)
        .surface(SurfaceType.INFO)
        .severity(FindingSeverity.MEDIUM)
        .title("AI Analysis Temporarily Unavailable")
        .aiExplanation(
            "The automated security analysis service is currently unavailable. "
                + "Please try running the scan again in a few minutes.")
        .technicalDetails("OpenAI API error: " + truncate(errorMessage))
        .remediationSteps(
            "1. Wait 5 minutes\n2. Re-run the scan\n3. Contact support if issue persists")
        .status(FindingStatus.OPEN)
        .build();
  }

  /**
   * Truncates error message to prevent excessively long database entries.
   *
   * @param text The text to truncate
   * @return Truncated text
   */
  private String truncate(String text) {
    if (text == null) {
      return "Unknown error";
    }
    if (text.length() <= 500) {
      return text;
    }
    return text.substring(0, 500 - 3) + "...";
  }
}
