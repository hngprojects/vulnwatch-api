package com.vulnwatch.worker.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/** Status of a security finding in the remediation workflow. */
@Schema(description = "Status of a security finding in the remediation workflow")
public enum FindingStatus {
  @Schema(description = "Finding has been identified and not yet fixed")
  OPEN,

  @Schema(description = "Finding has been fixed via PR or manual action")
  REMEDIATED,

  @Schema(description = "Finding has been marked as not applicable or acceptable risk")
  IGNORED
}
