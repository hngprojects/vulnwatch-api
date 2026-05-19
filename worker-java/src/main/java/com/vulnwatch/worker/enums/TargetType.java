package com.vulnwatch.worker.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "Type of target to scan")
public enum TargetType {

  @Schema(description = "Domain/website scanning (DNS, SSL, HTTP, CT Logs, Exposures)")
  DOMAIN("Domain"),

  @Schema(description = "Repository scanning (Dependencies, SAST, Secrets)")
  REPOSITORY("Repository");

  private final String name;

  TargetType(String name) {
    this.name = name;
  }
}