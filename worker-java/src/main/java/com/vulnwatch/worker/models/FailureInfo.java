package com.vulnwatch.worker.models;

import com.vulnwatch.worker.enums.SurfaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@Schema(description = "Information about a scanner that failed - preserves which surface failed")
public class FailureInfo {

  @Schema(description = "Name of the scanner that failed", example = "DnsScanner")
  private String scannerName;

  @Schema(
      description = "Surface type that failed - CRITICAL for knowing what went wrong",
      example = "DNS")
  private SurfaceType surface;

  @Schema(description = "Error message from the failure")
  private String errorMessage;

  @Schema(description = "When the failure occurred")
  private Instant failureTime;

  public FailureInfo(String scannerName, SurfaceType surface, String errorMessage) {
    this.scannerName = scannerName;
    this.surface = surface;
    this.errorMessage = errorMessage;
    this.failureTime = Instant.now();
  }
}
