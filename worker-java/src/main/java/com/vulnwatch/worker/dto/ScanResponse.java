package com.vulnwatch.worker.dto;

import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ScanResponse implements Scanner {
  private UUID scanId;
  private List<FindingResponse> findings = new ArrayList<>();

  @Override
  public ScanResult scan(ScanJob job) {
    return null;
  }

  @Override
  public TargetType getTargetType() {
    return null;
  }

  @Override
  public SurfaceType getSurfaceType() {
    return null;
  }
}
