package com.vulnwatch.worker.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Type of target to scan")
public enum TargetType {
  @Schema(description = "Domain/website scanning (DNS, SSL, HTTP, CT Logs, Exposures)")
  DOMAIN,

  @Schema(description = "Repository scanning (Dependencies, SAST, Secrets)")
  REPOSITORY
}
