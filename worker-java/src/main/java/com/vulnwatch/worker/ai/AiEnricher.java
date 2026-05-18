package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.converter.FindingsConverter;
import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ScanResult;
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
  public EnrichedScanResult enrichForSurface(AggregatedScanData aggregatedData) {
    UUID scanId = aggregatedData.getScanId();
    ScanResult scanResult = aggregatedData.getSuccessfulResults().getFirst();
    SurfaceType surface = scanResult.getSurface();
    Map<String, Object> rawData = scanResult.getRawData();

    log.info("Starting AI enrichment for scan: {}, surface: {}", scanId, surface);

    String prompt = promptBuilder.buildSingleSurfacePrompt(surface, rawData);
    var aiResponse = responseParser.callOpenAi(prompt);

    List<Finding> findings = findingsConverter.convertToFindings(scanId, aiResponse.getFindings());


    log.info("AI enrichment completed for scan: {}, surface: {}, findings: {}",
            scanId, surface, findings.size());

    return EnrichedScanResult.builder()
            .scanId(scanId)
            .surface(surface)
            .securityScore(0)  // Not used – score calculated later
            .findings(findings)
            .processedAt(Instant.now())
            .build();
  }


}
