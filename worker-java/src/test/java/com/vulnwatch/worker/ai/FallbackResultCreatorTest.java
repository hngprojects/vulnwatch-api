package com.vulnwatch.worker.ai;


import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.FindingStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

@DisplayName("FallbackResultCreator")
class FallbackResultCreatorTest {

    private FallbackResultCreator creator;
    private UUID scanId;

    @BeforeEach
    void setUp() {
        creator = new FallbackResultCreator();
        scanId = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────────────────────
    // create() — full-scan fallback
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("create() — full scan fallback")
    class CreateFullScan {

        @Test
        @DisplayName("returns EnrichedScanResult with correct scanId")
        void returnsScanId() {
            EnrichedScanResult result = creator.create(scanId, "some error");

            assertThat(result.getScanId()).isEqualTo(scanId);
        }

        @Test
        @DisplayName("sets security score to 50")
        void setsDefaultSecurityScore() {
            EnrichedScanResult result = creator.create(scanId, "error");

            assertThat(result.getSecurityScore()).isEqualTo(50);
        }

        @Test
        @DisplayName("marks result as fallback")
        void marksIsFallback() {
            EnrichedScanResult result = creator.create(scanId, "error");

            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("stores fallback reason as the error message")
        void storesFallbackReason() {
            String error = "OpenAI rate limit exceeded";
            EnrichedScanResult result = creator.create(scanId, error);

            assertThat(result.getFallbackReason()).isEqualTo(error);
        }

        @Test
        @DisplayName("sets processedAt close to now")
        void setsProcessedAtNow() {
            Instant before = Instant.now();
            EnrichedScanResult result = creator.create(scanId, "error");
            Instant after = Instant.now();

            assertThat(result.getProcessedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("contains exactly one finding")
        void containsOneFinding() {
            EnrichedScanResult result = creator.create(scanId, "error");

            assertThat(result.getFindings()).hasSize(1);
        }

        @Test
        @DisplayName("finding has INFO surface type")
        void findingHasInfoSurface() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getSurface()).isEqualTo(SurfaceType.INFO);
        }

        @Test
        @DisplayName("finding has MEDIUM severity")
        void findingHasMediumSeverity() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getSeverity()).isEqualTo(FindingSeverity.MEDIUM);
        }

        @Test
        @DisplayName("finding has OPEN status")
        void findingHasOpenStatus() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getStatus()).isEqualTo(FindingStatus.OPEN);
        }

        @Test
        @DisplayName("finding title indicates AI analysis unavailable")
        void findingTitleDescribesUnavailability() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getTitle()).isEqualTo("AI Analysis Temporarily Unavailable");
        }

        @Test
        @DisplayName("finding has a non-null UUID id")
        void findingHasId() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getId()).isNotNull();
        }

        @Test
        @DisplayName("finding has scanId matching the request")
        void findingHasScanId() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getScanId()).isEqualTo(scanId);
        }

        @Test
        @DisplayName("finding technicalDetails includes the error message")
        void findingTechnicalDetailsContainsError() {
            String error = "Connection timeout";
            Finding finding = creator.create(scanId, error).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).contains(error);
        }

        @Test
        @DisplayName("finding remediationSteps are non-blank and structured")
        void findingHasRemediationSteps() {
            Finding finding = creator.create(scanId, "error").getFindings().get(0);

            assertThat(finding.getRemediationSteps())
                    .isNotBlank()
                    .contains("1.")
                    .contains("2.")
                    .contains("3.");
        }

        @Test
        @DisplayName("two calls produce different finding IDs")
        void findingIdsAreUnique() {
            UUID id1 = creator.create(scanId, "err").getFindings().get(0).getId();
            UUID id2 = creator.create(scanId, "err").getFindings().get(0).getId();

            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("null error message is handled without exception")
        void handlesNullErrorMessage() {
            assertThat(creator.create(scanId, null)).isNotNull();
        }

        @Test
        @DisplayName("null error message surfaces 'Unknown error' in technicalDetails")
        void nullErrorYieldsUnknownError() {
            Finding finding = creator.create(scanId, null).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).contains("Unknown error");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // createForSurface()
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("createForSurface() — per-surface fallback")
    class CreateForSurface {

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("surface is stored on the result for every SurfaceType")
        void surfaceStoredOnResult(SurfaceType surface) {
            EnrichedScanResult result = creator.createForSurface(scanId, surface, "err");

            assertThat(result.getSurface()).isEqualTo(surface);
        }

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("finding surface matches the requested surface")
        void findingSurfaceMatchesRequested(SurfaceType surface) {
            Finding finding = creator.createForSurface(scanId, surface, "err")
                    .getFindings().get(0);

            assertThat(finding.getSurface()).isEqualTo(surface);
        }

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("finding title contains the surface name")
        void findingTitleContainsSurfaceName(SurfaceType surface) {
            Finding finding = creator.createForSurface(scanId, surface, "err")
                    .getFindings().get(0);

            assertThat(finding.getTitle()).contains(surface.name());
        }

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("finding aiExplanation mentions the surface name")
        void findingExplanationMentionsSurface(SurfaceType surface) {
            Finding finding = creator.createForSurface(scanId, surface, "err")
                    .getFindings().get(0);

            assertThat(finding.getAiExplanation()).contains(surface.name());
        }

        @Test
        @DisplayName("result is marked as fallback")
        void resultIsMarkedFallback() {
            EnrichedScanResult result = creator.createForSurface(scanId, SurfaceType.DNS, "err");

            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("fallback reason equals the error message")
        void fallbackReasonEqualsError() {
            String error = "Timeout";
            EnrichedScanResult result = creator.createForSurface(scanId, SurfaceType.DNS, error);

            assertThat(result.getFallbackReason()).isEqualTo(error);
        }

        @Test
        @DisplayName("security score defaults to 50")
        void defaultSecurityScore() {
            assertThat(creator.createForSurface(scanId, SurfaceType.SSL, "err")
                    .getSecurityScore()).isEqualTo(50);
        }

        @Test
        @DisplayName("finding has MEDIUM severity")
        void findingIsMedium() {
            Finding finding = creator.createForSurface(scanId, SurfaceType.SSL, "err")
                    .getFindings().get(0);

            assertThat(finding.getSeverity()).isEqualTo(FindingSeverity.MEDIUM);
        }

        @Test
        @DisplayName("finding has OPEN status")
        void findingIsOpen() {
            Finding finding = creator.createForSurface(scanId, SurfaceType.SSL, "err")
                    .getFindings().get(0);

            assertThat(finding.getStatus()).isEqualTo(FindingStatus.OPEN);
        }

        @Test
        @DisplayName("null error is handled without exception")
        void handlesNullError() {
            assertThat(creator.createForSurface(scanId, SurfaceType.DNS, null)).isNotNull();
        }

        @Test
        @DisplayName("null error surfaces 'Unknown error' in technicalDetails")
        void nullErrorSurfacesUnknown() {
            Finding finding = creator.createForSurface(scanId, SurfaceType.DNS, null)
                    .getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).contains("Unknown error");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // createScannerFailureFinding()
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("createScannerFailureFinding() — permanent scanner failure")
    class CreateScannerFailureFinding {

        @Test
        @DisplayName("returns a Finding (not an EnrichedScanResult)")
        void returnsFindings() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding).isNotNull();
        }

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("finding surface matches each SurfaceType")
        void findingSurfaceMatchesRequested(SurfaceType surface) {
            Finding finding = creator.createScannerFailureFinding(scanId, surface, "err");

            assertThat(finding.getSurface()).isEqualTo(surface);
        }

        @ParameterizedTest(name = "surface={0}")
        @EnumSource(SurfaceType.class)
        @DisplayName("title contains the surface name and 'Scanner Failed Permanently'")
        void titleContainsSurfaceAndFailedMessage(SurfaceType surface) {
            Finding finding = creator.createScannerFailureFinding(scanId, surface, "err");

            assertThat(finding.getTitle())
                    .contains(surface.name())
                    .contains("Scanner Failed Permanently");
        }

        @Test
        @DisplayName("scanId is preserved")
        void scanIdPreserved() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding.getScanId()).isEqualTo(scanId);
        }

        @Test
        @DisplayName("finding has MEDIUM severity")
        void severityIsMedium() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding.getSeverity()).isEqualTo(FindingSeverity.MEDIUM);
        }

        @Test
        @DisplayName("finding has OPEN status")
        void statusIsOpen() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding.getStatus()).isEqualTo(FindingStatus.OPEN);
        }

        @Test
        @DisplayName("finding has a non-null UUID id")
        void hasUUID() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding.getId()).isNotNull();
        }

        @Test
        @DisplayName("aiExplanation includes the error message")
        void explanationContainsError() {
            String error = "Connection refused";
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, error);

            assertThat(finding.getAiExplanation()).contains(error);
        }

        @Test
        @DisplayName("technicalDetails includes the error message")
        void technicalDetailsContainsError() {
            String error = "Socket timeout";
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, error);

            assertThat(finding.getTechnicalDetails()).contains(error);
        }

        @Test
        @DisplayName("remediationSteps are structured with numbered items")
        void remediationStepsAreStructured() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "err");

            assertThat(finding.getRemediationSteps())
                    .contains("1.")
                    .contains("2.")
                    .contains("3.");
        }

        @Test
        @DisplayName("null error is handled without exception")
        void handlesNullError() {
            assertThat(creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, null))
                    .isNotNull();
        }

        @Test
        @DisplayName("null error surfaces 'Unknown error' in technicalDetails")
        void nullErrorYieldsUnknown() {
            Finding finding = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, null);

            assertThat(finding.getTechnicalDetails()).contains("Unknown error");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // truncate() edge cases — exercised through public methods
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Error message truncation (via public API)")
    class Truncation {

        @Test
        @DisplayName("message exactly 500 chars is NOT truncated")
        void exactly500CharsNotTruncated() {
            String exactly500 = "x".repeat(500);
            Finding finding = creator.create(scanId, exactly500).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).endsWith(exactly500);
        }

        @Test
        @DisplayName("message of 499 chars is NOT truncated")
        void lessThan500CharsNotTruncated() {
            String msg = "x".repeat(499);
            Finding finding = creator.create(scanId, msg).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).endsWith(msg);
        }

        @Test
        @DisplayName("message of 501 chars IS truncated to 500 with ellipsis")
        void moreThan500CharsTruncated() {
            String msg = "x".repeat(501);
            Finding finding = creator.create(scanId, msg).getFindings().get(0);

            // technicalDetails = "OpenAI API error: " + truncated, total truncated part <= 500
            String technicalDetails = finding.getTechnicalDetails();
            assertThat(technicalDetails).endsWith("...");

            // extract the truncated portion after the prefix
            String truncatedPart = technicalDetails.substring(technicalDetails.indexOf(": ") + 2);
            assertThat(truncatedPart.length()).isEqualTo(500);
        }

        @Test
        @DisplayName("very long message (2000 chars) is truncated to 500 with ellipsis")
        void veryLongMessageTruncated() {
            String longMsg = "a".repeat(2000);
            Finding finding = creator.create(scanId, longMsg).getFindings().get(0);

            String technicalDetails = finding.getTechnicalDetails();
            assertThat(technicalDetails).endsWith("...");

            String truncatedPart = technicalDetails.substring(technicalDetails.indexOf(": ") + 2);
            assertThat(truncatedPart.length()).isEqualTo(500);
        }

        @Test
        @DisplayName("empty string error message is handled without exception")
        void emptyStringError() {
            assertThat(creator.create(scanId, "")).isNotNull();
        }

        @Test
        @DisplayName("error message with only whitespace is preserved as-is")
        void whitespaceOnlyError() {
            String ws = "   ";
            Finding finding = creator.create(scanId, ws).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).contains(ws);
        }

        @Test
        @DisplayName("error message with special characters is preserved")
        void specialCharactersPreserved() {
            String special = "Error: <null> & \"broken\" 'value' \n\t end";
            Finding finding = creator.create(scanId, special).getFindings().get(0);

            assertThat(finding.getTechnicalDetails()).contains(special);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // IDs and immutability
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ID generation and immutability")
    class IdGeneration {

        @Test
        @DisplayName("each create() call produces a different finding ID")
        void createProducesDifferentIds() {
            UUID id1 = creator.create(scanId, "err").getFindings().get(0).getId();
            UUID id2 = creator.create(scanId, "err").getFindings().get(0).getId();

            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("each createForSurface() call produces a different finding ID")
        void createForSurfaceProducesDifferentIds() {
            UUID id1 = creator.createForSurface(scanId, SurfaceType.DNS, "e")
                    .getFindings().get(0).getId();
            UUID id2 = creator.createForSurface(scanId, SurfaceType.DNS, "e")
                    .getFindings().get(0).getId();

            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("each createScannerFailureFinding() call produces a different finding ID")
        void scannerFailureProducesDifferentIds() {
            UUID id1 = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "e").getId();
            UUID id2 = creator.createScannerFailureFinding(scanId, SurfaceType.HTTP_HEADERS, "e").getId();

            assertThat(id1).isNotEqualTo(id2);
        }
    }
}
