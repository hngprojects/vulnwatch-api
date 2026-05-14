package com.vulnwatch.worker.models;

import com.vulnwatch.worker.enums.SurfaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Aggregated results from all scanners for a single scan")
public class AggregatedScanData {

  @Schema(description = "Scan ID")
  private UUID scanId;

  @Schema(description = "Original scan job that triggered this scan")
  private ScanJob scanJob;

  @Schema(description = "Successful scanner results - each has SurfaceType")
  private List<ScanResult> successfulResults;

  @Schema(
      description =
          "Failed scanner results - each FAILURE PRESERVES SurfaceType so we know which scanner failed")
  private List<FailureInfo> failures;

  /** Returns true if at least one scanner succeeded. */
  public boolean hasSuccesses() {
    return successfulResults != null && !successfulResults.isEmpty();
  }

  /** Returns the set of surface types that succeeded. */
  public List<SurfaceType> getSuccessfulSurfaces() {
    if (successfulResults == null) return List.of();
    return successfulResults.stream().map(ScanResult::getSurface).toList();
  }

  /**
   * Returns the set of surface types that failed. This tells us EXACTLY which scanners failed (DNS,
   * SSL, etc.)
   */
  public List<SurfaceType> getFailedSurfaces() {
    if (failures == null) return List.of();
    return failures.stream().map(FailureInfo::getSurface).toList();
  }
}
