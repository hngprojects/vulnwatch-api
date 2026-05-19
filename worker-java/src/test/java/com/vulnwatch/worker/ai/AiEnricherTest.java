//package com.vulnwatch.worker.ai;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyInt;
//import static org.mockito.ArgumentMatchers.anyList;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.*;
//
//import com.vulnwatch.worker.converter.FindingsConverter;
//import com.vulnwatch.worker.entity.Finding;
//import com.vulnwatch.worker.enums.FindingSeverity;
//import com.vulnwatch.worker.enums.SurfaceType;
//import com.vulnwatch.worker.enums.TargetType;
//import com.vulnwatch.worker.models.AggregatedScanData;
//import com.vulnwatch.worker.models.ScanJob;
//import com.vulnwatch.worker.models.ScanResult;
//import com.vulnwatch.worker.models.ai.AiFinding;
//import com.vulnwatch.worker.models.ai.AiResponse;
//import com.vulnwatch.worker.models.ai.EnrichedScanResult;
//import java.time.Instant;
//import java.util.List;
//import java.util.Map;
//import java.util.UUID;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InOrder;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//@ExtendWith(MockitoExtension.class)
//class AiEnricherTest {
//
//  @Mock private PromptBuilder promptBuilder;
//
//  @Mock private AiResponseParser responseParser;
//
//  @Mock private FindingsConverter findingsConverter;
//
//  @Mock private ScoreCalculator scoreCalculator;
//
//  @Mock private FallbackResultCreator fallbackCreator;
//
//  @InjectMocks private AiEnricher aiEnricher;
//
//  private UUID scanId;
//  private AggregatedScanData aggregatedData;
//  private ScanJob scanJob;
//  private AiResponse aiResponse;
//  private List<Finding> findings;
//  private List<AiFinding> aiFindings;
//
//  @BeforeEach
//  void setUp() {
//    scanId = UUID.randomUUID();
//
//    scanJob =
//        ScanJob.builder()
//            .scanId(scanId)
//            .requestedBy(UUID.randomUUID())
//            .domain("example.com")
//            .scanTypes(List.of(TargetType.DOMAIN))
//            .enqueuedAt(Instant.now())
//            .build();
//
//    ScanResult dnsResult =
//        ScanResult.success(
//            scanId,
//            "DnsScanner",
//            SurfaceType.DNS,
//            Map.of("has_dmarc", false, "dnssec_enabled", false));
//
//    aggregatedData =
//        AggregatedScanData.builder()
//            .scanId(scanId)
//            .scanJob(scanJob)
//            .successfulResults(List.of(dnsResult))
//            .failures(List.of())
//            .build();
//
//    aiFindings =
//        List.of(
//            AiFinding.builder()
//                .severity("Medium")
//                .surface("DNS")
//                .title("Missing DMARC Policy")
//                .plainEnglishExplanation("Your domain is missing DMARC policy")
//                .technicalDetails("No DMARC TXT record found")
//                .remediationSteps(List.of("Add DMARC record", "Monitor reports"))
//                .build());
//
//    aiResponse = AiResponse.builder().securityScore(72).findings(aiFindings).build();
//
//    findings =
//        List.of(
//            Finding.builder()
//                .id(UUID.randomUUID())
//                .scanId(scanId)
//                .surface(SurfaceType.DNS)
//                .severity(FindingSeverity.MEDIUM)
//                .title("Missing DMARC Policy")
//                .aiExplanation("Your domain is missing DMARC policy")
//                .remediationSteps("Add DMARC record\nMonitor reports")
//                .status(com.vulnwatch.worker.enums.FindingStatus.OPEN)
//                .build());
//  }
//
//  @Test
//  void enrich_shouldReturnEnrichedResult_whenAllComponentsSucceed() {
//    // Given
//    String prompt = "Test prompt";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(aiResponse);
//    when(findingsConverter.convertToFindings(scanId, aiFindings)).thenReturn(findings);
//    when(scoreCalculator.calculateFinalScore(72, aiFindings)).thenReturn(72);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getScanId()).isEqualTo(scanId);
//    assertThat(result.getSecurityScore()).isEqualTo(72);
//    assertThat(result.getFindings()).hasSize(1);
//    assertThat(result.getFindings().getFirst().getTitle()).isEqualTo("Missing DMARC Policy");
//    assertThat(result.getProcessedAt()).isNotNull();
//
//    verify(promptBuilder).buildPrompt(aggregatedData);
//    verify(responseParser).callOpenAi(prompt);
//    verify(findingsConverter).convertToFindings(scanId, aiFindings);
//    verify(scoreCalculator).calculateFinalScore(72, aiFindings);
//    verify(fallbackCreator, never()).create(any(), any());
//  }
//
//  @Test
//  void enrich_shouldHandleZeroFindingsSuccessfully() {
//    // Given
//    AiResponse emptyResponse = AiResponse.builder().securityScore(100).findings(List.of()).build();
//    List<Finding> emptyFindings = List.of();
//
//    String prompt = "Test prompt";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(emptyResponse);
//    when(findingsConverter.convertToFindings(scanId, List.of())).thenReturn(emptyFindings);
//    when(scoreCalculator.calculateFinalScore(100, List.of())).thenReturn(100);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(100);
//    assertThat(result.getFindings()).isEmpty();
//  }
//
//  @Test
//  void enrich_shouldHandleNullFindingsList() {
//    // Given
//    AiResponse nullFindingsResponse =
//        AiResponse.builder().securityScore(100).findings(null).build();
//
//    String prompt = "Test prompt";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(nullFindingsResponse);
//    when(findingsConverter.convertToFindings(scanId, null)).thenReturn(List.of());
//    when(scoreCalculator.calculateFinalScore(100, null)).thenReturn(100);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(100);
//  }
//
//  // COMPONENT FAILURE TESTS
//
//  @Test
//  void enrich_shouldReturnFallbackResult_whenPromptBuilderThrowsException() {
//    // Given
//    String errorMessage = "Prompt builder failed";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//
//    verify(promptBuilder).buildPrompt(aggregatedData);
//    verify(responseParser, never()).callOpenAi(any());
//    verify(findingsConverter, never()).convertToFindings(any(), any());
//    verify(scoreCalculator, never()).calculateFinalScore(anyInt(), anyList());
//    verify(fallbackCreator).create(scanId, errorMessage);
//  }
//
//  @Test
//  void enrich_shouldReturnFallbackResult_whenResponseParserThrowsException() {
//    // Given
//    String prompt = "Test prompt";
//    String errorMessage = "OpenAI API timeout";
//
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//
//    verify(promptBuilder).buildPrompt(aggregatedData);
//    verify(responseParser).callOpenAi(prompt);
//    verify(findingsConverter, never()).convertToFindings(any(), any());
//    verify(scoreCalculator, never()).calculateFinalScore(anyInt(), anyList());
//    verify(fallbackCreator).create(scanId, errorMessage);
//  }
//
//  @Test
//  void enrich_shouldReturnFallbackResult_whenFindingsConverterThrowsException() {
//    // Given
//    String prompt = "Test prompt";
//    String errorMessage = "Findings conversion failed";
//
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(aiResponse);
//    when(findingsConverter.convertToFindings(scanId, aiFindings))
//        .thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//
//    verify(promptBuilder).buildPrompt(aggregatedData);
//    verify(responseParser).callOpenAi(prompt);
//    verify(findingsConverter).convertToFindings(scanId, aiFindings);
//    verify(scoreCalculator, never()).calculateFinalScore(anyInt(), anyList());
//    verify(fallbackCreator).create(scanId, errorMessage);
//  }
//
//  @Test
//  void enrich_shouldReturnFallbackResult_whenScoreCalculatorThrowsException() {
//    // Given
//    String prompt = "Test prompt";
//    String errorMessage = "Score calculation failed";
//
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(aiResponse);
//    when(findingsConverter.convertToFindings(scanId, aiFindings)).thenReturn(findings);
//    when(scoreCalculator.calculateFinalScore(72, aiFindings))
//        .thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//
//    verify(promptBuilder).buildPrompt(aggregatedData);
//    verify(responseParser).callOpenAi(prompt);
//    verify(findingsConverter).convertToFindings(scanId, aiFindings);
//    verify(scoreCalculator).calculateFinalScore(72, aiFindings);
//    verify(fallbackCreator).create(scanId, errorMessage);
//  }
//
//  @Test
//  void enrich_shouldReturnFallbackResultWithCorrectFields_whenExceptionOccurs() {
//    // Given
//    String errorMessage = "AI service unavailable";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult expectedFallback =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(expectedFallback);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getScanId()).isEqualTo(scanId);
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//  }
//
//  // VERIFICATION ORDER TESTS
//
//  @Test
//  void enrich_shouldCallComponentsInCorrectOrder() {
//    // Given
//    String prompt = "Test prompt";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(aiResponse);
//    when(findingsConverter.convertToFindings(scanId, aiFindings)).thenReturn(findings);
//    when(scoreCalculator.calculateFinalScore(72, aiFindings)).thenReturn(72);
//
//    // When
//    aiEnricher.enrich(aggregatedData);
//
//    // Then
//    InOrder inOrder =
//        inOrder(promptBuilder, responseParser, findingsConverter, scoreCalculator, fallbackCreator);
//    inOrder.verify(promptBuilder).buildPrompt(aggregatedData);
//    inOrder.verify(responseParser).callOpenAi(prompt);
//    inOrder.verify(findingsConverter).convertToFindings(scanId, aiFindings);
//    inOrder.verify(scoreCalculator).calculateFinalScore(72, aiFindings);
//    inOrder.verify(fallbackCreator, never()).create(any(), any());
//  }
//
//  //  NULL/EDGE CASE TESTS
//
//  @Test
//  void enrich_shouldHandleNullScanJobInAggregatedData() {
//    // Given
//    AggregatedScanData dataWithNullScanJob =
//        AggregatedScanData.builder()
//            .scanId(scanId)
//            .scanJob(null)
//            .successfulResults(List.of())
//            .failures(List.of())
//            .build();
//
//    String errorMessage = "Null scan job";
//    when(promptBuilder.buildPrompt(dataWithNullScanJob))
//        .thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(scanId, errorMessage)).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(dataWithNullScanJob);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(50);
//  }
//
//  @Test
//  void enrich_shouldHandleEmptySuccessfulResults() {
//    // Given
//    AggregatedScanData emptyData =
//        AggregatedScanData.builder()
//            .scanId(scanId)
//            .scanJob(scanJob)
//            .successfulResults(List.of())
//            .failures(List.of())
//            .build();
//
//    String prompt = "Test prompt with no data";
//    when(promptBuilder.buildPrompt(emptyData)).thenReturn(prompt);
//    when(responseParser.callOpenAi(prompt)).thenReturn(aiResponse);
//    when(findingsConverter.convertToFindings(scanId, aiFindings)).thenReturn(findings);
//    when(scoreCalculator.calculateFinalScore(72, aiFindings)).thenReturn(72);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(emptyData);
//
//    // Then
//    assertThat(result).isNotNull();
//    assertThat(result.getSecurityScore()).isEqualTo(72);
//  }
//
//  @Test
//  void enrich_shouldPreserveScanIdInFallbackResult() {
//    // Given
//    String errorMessage = "Complete failure";
//    when(promptBuilder.buildPrompt(aggregatedData)).thenThrow(new RuntimeException(errorMessage));
//
//    EnrichedScanResult fallbackResult =
//        EnrichedScanResult.builder()
//            .scanId(scanId)
//            .securityScore(50)
//            .findings(List.of())
//            .processedAt(Instant.now())
//            .build();
//    when(fallbackCreator.create(eq(scanId), anyString())).thenReturn(fallbackResult);
//
//    // When
//    EnrichedScanResult result = aiEnricher.enrich(aggregatedData);
//
//    // Then
//    assertThat(result.getScanId()).isEqualTo(scanId);
//  }
//}
