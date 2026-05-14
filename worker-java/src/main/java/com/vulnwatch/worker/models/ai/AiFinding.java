package com.vulnwatch.worker.models.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Individual finding from AI response. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFinding {

  private String severity;

  private String surface;

  private String title;

  @JsonProperty("plain_english_explanation")
  private String plainEnglishExplanation;

  @JsonProperty("technical_details")
  private String technicalDetails;

  @JsonProperty("remediation_steps")
  private List<String> remediationSteps;
}
