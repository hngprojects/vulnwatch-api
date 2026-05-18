package com.vulnwatch.worker.enums;

import com.vulnwatch.worker.exception.InvalidSurfaceTypeException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/** Surface types that can be scanned by different scanners. */
@Getter
@Schema(description = "Type of security surface being scanned")
public enum SurfaceType {

  @Schema(description = "DNS records and configurations")
  DNS("Dns"),

  @Schema(description = "SSL/TLS certificates and protocols")
  SSL("Ssl"),

  @Schema(description = "HTTP security headers")
  HTTP_HEADERS("HttpHeaders"),

  @Schema(description = "Dependency vulnerabilities")
  DEPENDENCY("Dependency"),

  @Schema(description = "Hardcoded secrets and credentials")
  SECRETS("Secrets"),

  @Schema(description = "Fallback for AI failure")
  INFO("Info");

  private final String name;

  SurfaceType(String name) {
    this.name = name;
  }

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