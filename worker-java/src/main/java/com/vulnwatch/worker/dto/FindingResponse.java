package com.vulnwatch.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FindingResponse {
  private String surface;
  private String severity;
  private String title;
  private String aiExplanation;
  private String technicalPayload;
  private String remediation;
}
