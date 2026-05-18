package com.vulnwatch.worker.models.ai;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.SurfaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@Schema(description = "AI enrichment result for a single surface")
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedScanResult {

  @Schema(description = "Scan ID")
  private UUID scanId;

  @Schema(description = "Which surface this result belongs to (DNS, SSL, etc.)")
  private SurfaceType surface;

  @Schema(description = "Security score for this surface (0-100)", minimum = "0", maximum = "100")
  private int securityScore;

  @Schema(description = "AI-generated findings for this surface")
  private List<Finding> findings;

  @Schema(description = "When enrichment was completed")
  private Instant processedAt;

  @Builder.Default
  @Schema(description = "Whether this result is a fallback (AI unavailable)")
  private boolean isFallback = false;

  @Schema(description = "Reason for fallback (if applicable)")
  private String fallbackReason;

  private Integer aiProvidedScore;
  private Integer ruleBasedScore;


  /**
   * Counts findings by severity.
   */
  public long countBySeverity(FindingSeverity severity) {
    if (findings == null) return 0;
    return findings.stream()
            .filter(f -> f.getSeverity() == severity)
            .count();
  }

  /**
   * Counts findings by surface.
   */
  public long countBySurface(SurfaceType surface) {
    if (findings == null) return 0;
    return findings.stream()
            .filter(f -> f.getSurface() == surface)
            .count();
  }

  /**
   * Returns a summary of findings for logging.
   */
  public String getSummary() {
    if (findings == null || findings.isEmpty()) {
      return "No findings";
    }

    long critical = countBySeverity(FindingSeverity.CRITICAL);
    long high = countBySeverity(FindingSeverity.HIGH);
    long medium = countBySeverity(FindingSeverity.MEDIUM);
    long low = countBySeverity(FindingSeverity.LOW);

    return String.format("Critical: %d, High: %d, Medium: %d, Low: %d",
            critical, high, medium, low);
  }
}