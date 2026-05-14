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
@Schema(description = "Final scan result after AI enrichment")
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedScanResult {

  @Schema(description = "Scan ID")
  private UUID scanId;

  @Schema(description = "Security score 0-100", minimum = "0", maximum = "100")
  private int securityScore;

  @Schema(description = "AI-generated findings")
  private List<Finding> findings;

  @Schema(description = "When enrichment was completed")
  private Instant processedAt;

  private Integer aiProvidedScore;
  private Integer ruleBasedScore;

  /** Counts findings by severity. */
  public long countBySeverity(FindingSeverity severity) {
    if (findings == null) return 0;
    return findings.stream().filter(f -> f.getSeverity() == severity).count();
  }

  /** Counts findings by surface. */
  public long countBySurface(SurfaceType surface) {
    if (findings == null) return 0;
    return findings.stream().filter(f -> f.getSurface() == surface).count();
  }

  /** Returns a summary of findings for logging. */
  public String getSummary() {
    if (findings == null || findings.isEmpty()) {
      return "No findings";
    }

    long critical = countBySeverity(FindingSeverity.CRITICAL);
    long high = countBySeverity(FindingSeverity.HIGH);
    long medium = countBySeverity(FindingSeverity.MEDIUM);
    long low = countBySeverity(FindingSeverity.LOW);

    return String.format(
        "Critical: %d, High: %d, Medium: %d, Low: %d", critical, high, medium, low);
  }
}
