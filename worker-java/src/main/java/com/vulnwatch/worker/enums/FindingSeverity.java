package com.vulnwatch.worker.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/** Severity levels for security findings. Critical/High require immediate attention. */
@Schema(description = "Severity level of a security finding")
public enum FindingSeverity {
  @Schema(description = "Immediate threat: exposed secrets, actively exploited vulnerabilities")
  CRITICAL,

  @Schema(
      description = "Significant risk: missing critical security headers, expiring certificates")
  HIGH,

  @Schema(description = "Moderate risk: missing security best practices, informational")
  MEDIUM,

  @Schema(description = "Low risk: minor improvements, recommendations")
  LOW
}
