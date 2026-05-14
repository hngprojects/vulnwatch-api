package com.vulnwatch.worker.entity;

import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.FindingStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Finding entity - matches the 'findings' table in PostgreSQL. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "findings")
@Schema(description = "Finding entity - matches the 'findings' table in PostgreSQL")
public class Finding {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Schema(description = "Primary key", example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID id;

  @Column(nullable = false)
  @Schema(description = "Foreign key to scans table")
  private UUID scanId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Schema(description = "Scanner surface that found this issue", example = "DNS")
  private SurfaceType surface;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Schema(description = "Severity level", example = "MEDIUM")
  private FindingSeverity severity;

  @Column(nullable = false)
  @Schema(description = "Short title of the finding", example = "Missing DMARC Policy")
  private String title;

  @Schema(description = "CVE ID (if applicable)", example = "CVE-2021-44228")
  private String cveId;

  @Column(columnDefinition = "TEXT")
  @Schema(description = "Plain English explanation from AI")
  private String aiExplanation;

  @Column(columnDefinition = "TEXT")
  @Schema(description = "Technical details from AI")
  private String technicalDetails;

  @Column(columnDefinition = "TEXT")
  @Schema(description = "Raw scanner JSON (optional, for debugging)")
  private String technicalPayload;

  @Column(columnDefinition = "TEXT")
  @Schema(description = "Step-by-step remediation steps")
  private String remediationSteps;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Schema(description = "Finding status", example = "OPEN")
  private FindingStatus status;

  public static Finding fromAiResponse(
      UUID scanId,
      String severityStr,
      String surfaceStr,
      String title,
      String explanation,
      String remediationSteps) {
    FindingSeverity severity;
    try {
      severity = FindingSeverity.valueOf(severityStr.toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      severity = FindingSeverity.MEDIUM;
    }

    SurfaceType surface = SurfaceType.valueOf(surfaceStr.toUpperCase());

    return Finding.builder()
        .scanId(scanId)
        .surface(surface)
        .severity(severity)
        .title(title)
        .aiExplanation(explanation)
        .remediationSteps(remediationSteps)
        .status(FindingStatus.OPEN)
        .build();
  }

  public static Finding createAiFailureFallback(UUID scanId, String errorMessage) {
    return Finding.builder()
        .scanId(scanId)
        .surface(SurfaceType.DNS)
        .severity(FindingSeverity.MEDIUM)
        .title("AI Analysis Temporarily Unavailable")
        .aiExplanation("The automated security analysis service encountered an error.")
        .technicalDetails("OpenAI API error: " + errorMessage)
        .remediationSteps("1. Wait 5 minutes\n2. Re-run the scan")
        .status(FindingStatus.OPEN)
        .build();
  }

  public static Finding createTimeoutFinding(UUID scanId, SurfaceType surface) {
    return Finding.builder()
        .scanId(scanId)
        .surface(surface)
        .severity(FindingSeverity.MEDIUM)
        .title(surface + " Scanner Timeout")
        .aiExplanation("The " + surface + " scanner timed out.")
        .technicalDetails("Scanner timeout exceeded 30 seconds.")
        .remediationSteps("1. Check target accessibility\n2. Re-run scan")
        .status(FindingStatus.OPEN)
        .build();
  }

  public static Finding createFailureFinding(
      UUID scanId, SurfaceType surface, String errorMessage) {
    return Finding.builder()
        .scanId(scanId)
        .surface(surface)
        .severity(FindingSeverity.LOW)
        .title(surface + " Scanner Failed")
        .aiExplanation("The " + surface + " scanner encountered an error.")
        .technicalDetails("Error: " + errorMessage)
        .remediationSteps("1. Re-run the scan")
        .status(FindingStatus.OPEN)
        .build();
  }
}
