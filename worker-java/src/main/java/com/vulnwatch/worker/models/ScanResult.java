package com.vulnwatch.worker.models;

import com.vulnwatch.worker.enums.SurfaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Raw result from a single scanner before AI enrichment. This is what each scanner returns to the
 * orchestrator. Other team members implement scanners that return this object.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Raw result from a single scanner before AI enrichment")
public class ScanResult {

  @Schema(
      description = "Scan ID this result belongs to",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID scanId;

  @Schema(description = "Name of the scanner that produced this result", example = "DnsScanner")
  private String scannerName;

  @Schema(description = "Type of surface that was scanned", example = "DNS")
  private SurfaceType surface;

  @Schema(description = "When the scan was performed", example = "2026-05-11T10:00:00Z")
  private Instant timestamp;

  @Schema(description = "Whether the scanner completed successfully")
  private boolean success;

  @Schema(
      description = "Error message if success=false",
      example = "DNS lookup timeout after 10 seconds")
  private String errorMessage;

  @Builder.Default
  @Schema(description = "Raw technical data from the scanner (varies by scanner type)")
  private Map<String, Object> rawData = new HashMap<>();

  /**
   * Creates a successful scan result.
   *
   * @param scanId The scan ID
   * @param scannerName Name of the scanner
   * @param surface Surface type scanned
   * @param rawData Raw technical data
   * @return A successful ScanResult
   */
  public static ScanResult success(
      UUID scanId, String scannerName, SurfaceType surface, Map<String, Object> rawData) {
    return ScanResult.builder()
        .scanId(scanId)
        .scannerName(scannerName)
        .surface(surface)
        .timestamp(Instant.now())
        .success(true)
        .rawData(rawData != null ? rawData : new HashMap<>())
        .build();
  }

  /**
   * Creates a failed scan result from an exception.
   *
   * @param scanId The scan ID
   * @param scannerName Name of the scanner
   * @param surface Surface type scanned
   * @param error The exception or error message
   * @return A failed ScanResult
   */
  public static ScanResult failure(
      UUID scanId, String scannerName, SurfaceType surface, Throwable error) {
    return ScanResult.builder()
        .scanId(scanId)
        .scannerName(scannerName)
        .surface(surface)
        .timestamp(Instant.now())
        .success(false)
        .errorMessage(error != null ? error.getMessage() : "Unknown error")
        .rawData(new HashMap<>())
        .build();
  }

  /**
   * Creates a timeout result for a scanner that exceeded its time limit.
   *
   * @param scanId The scan ID
   * @param scannerName Name of the scanner
   * @param surface Surface type scanned
   * @param timeoutSeconds The timeout that was exceeded
   * @return A timeout ScanResult
   */
  public static ScanResult timeout(
      UUID scanId, String scannerName, SurfaceType surface, int timeoutSeconds) {
    return ScanResult.builder()
        .scanId(scanId)
        .scannerName(scannerName)
        .surface(surface)
        .timestamp(Instant.now())
        .success(false)
        .errorMessage(String.format("Scanner timeout after %d seconds", timeoutSeconds))
        .rawData(new HashMap<>())
        .build();
  }
}
