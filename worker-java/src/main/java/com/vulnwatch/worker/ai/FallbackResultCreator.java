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

@Slf4j
@Component
public class FallbackResultCreator {

  private static final int FALLBACK_SECURITY_SCORE = 50;

  /**
   * Creates a fallback enriched result for full scan (legacy).
   */
  public EnrichedScanResult create(UUID scanId, String errorMessage) {
    log.warn("Creating fallback result for scan: {} due to error: {}", scanId, errorMessage);
    Finding fallbackFinding = createFallbackFinding(scanId, errorMessage);
    return EnrichedScanResult.builder()
            .scanId(scanId)
            .securityScore(FALLBACK_SECURITY_SCORE)
            .findings(List.of(fallbackFinding))
            .isFallback(true)
            .fallbackReason(errorMessage)
            .processedAt(Instant.now())
            .build();
  }

  /**
   * Creates a fallback enriched result for a single surface.
   * Returns EnrichedScanResult (not Finding) so circuit breaker can return it.
   */
  public EnrichedScanResult createForSurface(UUID scanId, SurfaceType surface, String errorMessage) {
    log.warn("Creating fallback result for scan: {}, surface: {} due to error: {}", scanId, surface, errorMessage);
    Finding fallbackFinding = createFallbackFindingForSurface(scanId, surface, errorMessage);
    return EnrichedScanResult.builder()
            .scanId(scanId)
            .surface(surface)
            .securityScore(FALLBACK_SECURITY_SCORE)
            .findings(List.of(fallbackFinding))
            .isFallback(true)
            .fallbackReason(errorMessage)
            .processedAt(Instant.now())
            .build();
  }

  /**
   * Creates a scanner failure finding (when scanner exhausts retries).
   * Returns Finding directly (not EnrichedScanResult) because it's saved separately.
   */
  public Finding createScannerFailureFinding(UUID scanId, SurfaceType surface, String errorMessage) {
    log.warn("Creating scanner failure finding for scan: {}, surface: {}", scanId, surface);
    return Finding.builder()
            .id(UUID.randomUUID())
            .scanId(scanId)
            .surface(surface)
            .severity(FindingSeverity.MEDIUM)
            .title(surface.name() + " Scanner Failed Permanently")
            .aiExplanation(
                    "The " + surface.name() + " scanner could not complete after multiple attempts. " +
                            "Error: " + errorMessage + ". Please check your domain configuration and try again later.")
            .technicalDetails("Scanner error: " + truncate(errorMessage))
            .remediationSteps(
                    "1. Verify your domain is accessible\n" +
                            "2. Check network connectivity\n" +
                            "3. If the issue persists, contact support")
            .status(FindingStatus.OPEN)
            .build();
  }

  private Finding createFallbackFinding(UUID scanId, String errorMessage) {
    return Finding.builder()
            .id(UUID.randomUUID())
            .scanId(scanId)
            .surface(SurfaceType.INFO)
            .severity(FindingSeverity.MEDIUM)
            .title("AI Analysis Temporarily Unavailable")
            .aiExplanation("The automated security analysis service is currently unavailable.")
            .technicalDetails("OpenAI API error: " + truncate(errorMessage))
            .remediationSteps("1. Wait 5 minutes\n2. Re-run the scan\n3. Contact support")
            .status(FindingStatus.OPEN)
            .build();
  }

  private Finding createFallbackFindingForSurface(UUID scanId, SurfaceType surface, String errorMessage) {
    return Finding.builder()
            .id(UUID.randomUUID())
            .scanId(scanId)
            .surface(surface)
            .severity(FindingSeverity.MEDIUM)
            .title(surface.name() + " Analysis Temporarily Unavailable")
            .aiExplanation("The automated security analysis for " + surface.name() + " is currently unavailable.")
            .technicalDetails("OpenAI API error: " + truncate(errorMessage))
            .remediationSteps("1. Wait 5 minutes\n2. Re-run the scan\n3. Contact support")
            .status(FindingStatus.OPEN)
            .build();
  }

  private String truncate(String text) {
    if (text == null) return "Unknown error";
    if (text.length() <= 500) return text;
    return text.substring(0, 500 - 3) + "...";
  }
}