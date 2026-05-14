package com.vulnwatch.worker.models.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Response POJO - matches the JSON structure returned by OpenAI.
 *
 * <p>Example JSON from OpenAI:
 *
 * <pre>
 * {
 *   "security_score": 72,
 *   "findings": [
 *     {
 *       "severity": "Medium",
 *       "surface": "DNS",
 *       "title": "Missing DMARC Policy",
 *       "plain_english_explanation": "Your domain is missing DMARC...",
 *       "technical_details": "No DMARC TXT record found...",
 *       "remediation_steps": ["Step 1", "Step 2"]
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiResponse {

  @JsonProperty("security_score")
  private int securityScore;

  private List<AiFinding> findings;
}
