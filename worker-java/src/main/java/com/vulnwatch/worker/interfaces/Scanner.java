package com.vulnwatch.worker.interfaces;

import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Contract for all security scanners - pluggable scan components")
public interface Scanner {

  @Schema(description = "Performs the security scan and returns raw technical data")
  ScanResult scan(ScanJob job);

  @Schema(description = "Returns the target type this scanner supports (DOMAIN or REPOSITORY)")
  TargetType getTargetType();

  @Schema(description = "Returns the surface type of this scanner (DNS, SSL e.g)")
  SurfaceType getSurfaceType();
}
