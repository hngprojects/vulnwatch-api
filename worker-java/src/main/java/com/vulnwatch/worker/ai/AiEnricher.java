package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Facade that orchestrates the entire AI enrichment process. Each component is injected and can be
 * unit tested independently.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>PromptBuilder builds AI prompt from raw scan data
 *   <li>AiResponseParser calls OpenAI and parses response
 *   <li>FindingsConverter converts AI findings to database entities
 *   <li>ScoreCalculator validates and normalizes security score
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEnricher {

  private final PromptBuilder promptBuilder;
  private final AiResponseParser responseParser;
  private final FindingsConverter findingsConverter;
  private final ScoreCalculator scoreCalculator;
  private final FallbackResultCreator fallbackCreator;

  /**
   * Enriches raw scan data with AI-generated findings.
   *
   * @param aggregatedData Raw results from all scanners
   * @return Enriched result with security score and findings
   */
  public EnrichedScanResult enrich(AggregatedScanData aggregatedData) {
    UUID scanId = aggregatedData.getScanId();
    log.info("Starting AI enrichment for scan: {}", scanId);

    try {

      String prompt = promptBuilder.buildPrompt(aggregatedData);
      log.debug("Built prompt for scan: {}, length: {}", scanId, prompt.length());

      var aiResponse = responseParser.callOpenAi(prompt);

      var aiFindings = aiResponse.getFindings();
      int findingsCount = (aiFindings == null) ? 0 : aiFindings.size();
      log.debug("Received AI response for scan: {}, findings: {}", scanId, findingsCount);

      List<Finding> findings =
          findingsConverter.convertToFindings(scanId, aiResponse.getFindings());
      log.debug("Converted {} findings for scan: {}", findings.size(), scanId);

      int securityScore =
          scoreCalculator.calculateFinalScore(
              aiResponse.getSecurityScore(), aiResponse.getFindings());

      log.info(
          "AI enrichment completed for scan: {} - score: {}, findings: {}",
          scanId,
          securityScore,
          findings.size());

      return EnrichedScanResult.builder()
          .scanId(scanId)
          .securityScore(securityScore)
          .findings(findings)
          .processedAt(Instant.now())
          .build();

    } catch (Exception e) {
      log.error("AI enrichment failed for scan: {}", scanId, e);
      return fallbackCreator.create(scanId, e.getMessage());
    }
  }

  public void enrichForSurface(UUID scanId, SurfaceType surface, Map<String, Object> stringObjectMap) {
  }
}
