package com.vulnwatch.worker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

  private PromptBuilder promptBuilder;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    promptBuilder = new PromptBuilder(objectMapper);
  }

  @Test
  void buildPrompt_shouldContainRoleInstruction() {
    // Given
    AggregatedScanData aggregatedData = createMockAggregatedData();

    // When
    String prompt = promptBuilder.buildPrompt(aggregatedData);

    // Then
    assertThat(prompt).contains("VulnWatch AI");
    assertThat(prompt).contains("world-class security expert");
    assertThat(prompt).contains("The 'severity' field MUST be exactly one of");
    assertThat(prompt).contains("The 'surface' field MUST be exactly one of");
    assertThat(prompt).contains("DNS, SSL, HTTP_HEADERS");
  }

  @Test
  void buildPrompt_shouldContainScanDataJson() {
    // Given
    AggregatedScanData aggregatedData = createMockAggregatedData();

    // When
    String prompt = promptBuilder.buildPrompt(aggregatedData);

    // Then -
    assertThat(prompt).contains("domain");
    assertThat(prompt).contains("example.com");
  }

  @Test
  void convertScanDataToJson_shouldReturnValidJson() {
    // Given
    AggregatedScanData aggregatedData = createMockAggregatedData();

    // When
    String json = promptBuilder.convertScanDataToJson(aggregatedData);

    // Then
    assertThat(json).isNotEmpty();
    assertThat(json).startsWith("{");
    assertThat(json).endsWith("}");
  }

  @Test
  void convertScanDataToJson_shouldGroupResultsBySurface() {
    // Given
    UUID scanId = UUID.randomUUID();

    ScanResult dnsResult =
        ScanResult.success(
            scanId, "DnsScanner", SurfaceType.DNS, Map.of("has_dmarc", false, "has_spf", true));

    ScanResult sslResult =
        ScanResult.success(
            scanId,
            "SslScanner",
            SurfaceType.SSL,
            Map.of("expiry_days", 25, "weak_protocols", List.of("TLSv1.0")));

    AggregatedScanData aggregatedData =
        AggregatedScanData.builder()
            .scanId(scanId)
            .successfulResults(List.of(dnsResult, sslResult))
            .failures(List.of())
            .build();

    // When
    String json = promptBuilder.convertScanDataToJson(aggregatedData);

    // Then
    assertThat(json).contains("\"dns\"");
    assertThat(json).contains("\"ssl\"");
    assertThat(json).contains("has_dmarc");
    assertThat(json).contains("expiry_days");
  }

  @Test
  void convertScanDataToJson_shouldReturnEmptyJson_whenNoSuccessfulResults() {
    // Given
    UUID scanId = UUID.randomUUID();

    AggregatedScanData aggregatedData =
        AggregatedScanData.builder()
            .scanId(scanId)
            .successfulResults(List.of())
            .failures(List.of())
            .build();

    // When
    String json = promptBuilder.convertScanDataToJson(aggregatedData);

    // Then
    assertThat(json).isEqualTo("{}");
  }

  @Test
  void convertScanDataToJson_shouldHandleNullRawData() {
    // Given
    UUID scanId = UUID.randomUUID();

    ScanResult nullResult =
        ScanResult.builder()
            .scanId(scanId)
            .scannerName("DnsScanner")
            .surface(SurfaceType.DNS)
            .success(true)
            .rawData(null)
            .build();

    AggregatedScanData aggregatedData =
        AggregatedScanData.builder()
            .scanId(scanId)
            .successfulResults(List.of(nullResult))
            .failures(List.of())
            .build();

    // When
    String json = promptBuilder.convertScanDataToJson(aggregatedData);

    // Then
    assertThat(json).isNotEqualTo("{}");
  }

  @Test
  void convertScanDataToJson_shouldPreserveOrderOfSurfaces() {
    // Given
    UUID scanId = UUID.randomUUID();

    ScanResult dnsResult =
        ScanResult.success(
            scanId, "DnsScanner", SurfaceType.DNS, Map.of("record_a", "93.184.216.34"));

    ScanResult sslResult =
        ScanResult.success(scanId, "SslScanner", SurfaceType.SSL, Map.of("expiry_days", 25));

    ScanResult httpResult =
        ScanResult.success(
            scanId, "HttpScanner", SurfaceType.HTTP_HEADERS, Map.of("has_csp", false));

    AggregatedScanData aggregatedData =
        AggregatedScanData.builder()
            .scanId(scanId)
            .successfulResults(List.of(dnsResult, sslResult, httpResult))
            .failures(List.of())
            .build();

    // When
    String json = promptBuilder.convertScanDataToJson(aggregatedData);

    // Then
    int dnsIndex = json.indexOf("\"dns\"");
    int sslIndex = json.indexOf("\"ssl\"");
    int httpIndex = json.indexOf("\"http_headers\"");

    assertThat(dnsIndex).isLessThan(sslIndex);
    assertThat(sslIndex).isLessThan(httpIndex);
  }

  private AggregatedScanData createMockAggregatedData() {
    UUID scanId = UUID.randomUUID();

    ScanJob scanJob =
        ScanJob.builder()
            .scanId(scanId)
            .requestedBy(UUID.randomUUID())
            .domain("example.com")
            .scanTypes(List.of(TargetType.DOMAIN))
            .enqueuedAt(Instant.now())
            .build();

    ScanResult dnsResult =
        ScanResult.success(
            scanId,
            "DnsScanner",
            SurfaceType.DNS,
            Map.of("has_dmarc", false, "dnssec_enabled", false));

    return AggregatedScanData.builder()
        .scanId(scanId)
        .scanJob(scanJob)
        .successfulResults(List.of(dnsResult))
        .failures(List.of())
        .build();
  }
}
