package com.vulnwatch.worker.enums;

import com.vulnwatch.worker.exception.InvalidSurfaceTypeException;
import io.swagger.v3.oas.annotations.media.Schema;

/** Surface types that can be scanned by different scanners. */
@Schema(description = "Type of security surface being scanned")
public enum SurfaceType {
  @Schema(description = "DNS records and configurations")
  DNS,

  @Schema(description = "SSL/TLS certificates and protocols")
  SSL,

  @Schema(description = "HTTP security headers")
  HTTP_HEADERS,

  @Schema(description = "Dependency vulnerabilities")
  DEPENDENCY,

  @Schema(description = "Hardcoded secrets and credentials")
  SECRETS,

  @Schema(description = "Fallback for AI failure")
  INFO;

  /**
   * Converts a string to SurfaceType regardless of case.
   *
   * @param value The string value (e.g., "dns", "Dns", "DNS")
   * @return The matching SurfaceType
   * @throws InvalidSurfaceTypeException if no match is found
   */
  public static SurfaceType fromString(String value){
    if (value == null) {
      throw new InvalidSurfaceTypeException("Surface type cannot be null");
    }

    for (SurfaceType type : SurfaceType.values()) {
      if (type.name().equalsIgnoreCase(value.trim())) {
        return type;
      }
    }

    throw new InvalidSurfaceTypeException("Unknown surface type: " + value);
  }
}
