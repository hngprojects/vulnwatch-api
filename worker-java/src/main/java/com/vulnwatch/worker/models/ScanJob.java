package com.vulnwatch.worker.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vulnwatch.worker.enums.TargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ScanJob model - matches the JSON payload from C# API.
 *
 * <p>C# publishes to Redis channel "scan-jobs" with structure:
 *
 * <pre>
 * {
 *   "scan_id": "uuid",
 *   "domain": "example.com",
 *   "scan_types": ["DOMAIN"],
 *   "requested_by": "user-id",
 *   "enqueuedAt": "2026-05-11T10:00:00Z"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Scan job from C# API via Redis channel 'scan-jobs'")
public class ScanJob {

  @JsonProperty("scan_id")
  @Schema(
      description = "Unique identifier for this scan",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID scanId;

  @JsonProperty("domain")
  @Schema(description = "Domain name to scan (for DOMAIN type)", example = "example.com")
  private String domain;

  @JsonProperty("scan_types")
  @Schema(description = "List of scan types to perform", example = "[\"DOMAIN\"]")
  private List<TargetType> scanTypes;

  @JsonProperty("requested_by")
  @Schema(
      description = "ID of the user who requested the scan",
      example = "550e8400-e29b-41d4-a716-446655440001")
  private UUID requestedBy;

  @JsonProperty("enqueuedAt")
  @Schema(description = "When the scan was enqueued by C#", example = "2026-05-11T10:00:00Z")
  private Instant enqueuedAt;

  public boolean isDomainScan() {
    return scanTypes != null && scanTypes.contains(TargetType.DOMAIN);
  }

  public boolean isRepositoryScan() {
    return scanTypes != null && scanTypes.contains(TargetType.REPOSITORY);
  }
}
