package com.vulnwatch.worker.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/** Status of a security finding in the remediation workflow. */
@Getter
@Schema(description = "Status of a security finding in the remediation workflow")
public enum FindingStatus {

  @Schema(description = "Finding has been identified and not yet fixed")
  OPEN("Open"),

  @Schema(description = "Finding has been fixed via PR or manual action")
  REMEDIATED("Remediated"),

  @Schema(description = "Finding has been marked as not applicable or acceptable risk")
  IGNORED("Ignored");

  private final String name;

  FindingStatus(String name) {
    this.name = name;
  }
}