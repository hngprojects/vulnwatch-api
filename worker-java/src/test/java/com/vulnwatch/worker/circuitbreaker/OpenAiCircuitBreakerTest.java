package com.vulnwatch.worker.circuitbreaker;

import com.vulnwatch.worker.ai.AiEnricher;
import com.vulnwatch.worker.ai.FallbackResultCreator;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAiCircuitBreaker")
class OpenAiCircuitBreakerTest {

    @Mock private AiEnricher aiEnricher;
    @Mock private FallbackResultCreator fallbackCreator;

    // Use a real CircuitBreaker so state transitions are testable
    private CircuitBreaker circuitBreaker;
    private OpenAiCircuitBreaker openAiCircuitBreaker;

    private UUID scanId;
    private AggregatedScanData aggregatedData;
    private EnrichedScanResult successResult;
    private EnrichedScanResult fallbackResult;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.ofDefaults("test");

        openAiCircuitBreaker = new OpenAiCircuitBreaker(aiEnricher, fallbackCreator, circuitBreaker);

        scanId = UUID.randomUUID();


        ScanResult scanResult = ScanResult.success(scanId, "TestScanner", SurfaceType.SSL, null);
        aggregatedData = mock(AggregatedScanData.class);
        lenient().when(aggregatedData.getScanId()).thenReturn(scanId);
        lenient().when(aggregatedData.getSuccessfulResults()).thenReturn(List.of(scanResult));

        successResult = EnrichedScanResult.builder()
                .scanId(scanId)
                .surface(SurfaceType.SSL)
                .isFallback(false)
                .build();

        fallbackResult = EnrichedScanResult.builder()
                .scanId(scanId)
                .surface(SurfaceType.SSL)
                .isFallback(true)
                .fallbackReason("AI unavailable")
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // enrichWithCircuitBreaker() — happy path
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("enrichWithCircuitBreaker() — success path")
    class SuccessPath {

        @Test
        @DisplayName("returns AI result when enricher succeeds")
        void returnsAiResultOnSuccess() {
            when(aiEnricher.enrichForSurface(aggregatedData)).thenReturn(successResult);

            EnrichedScanResult result = openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(result).isEqualTo(successResult);
        }

        @Test
        @DisplayName("delegates to aiEnricher.enrichForSurface with the aggregated data")
        void delegatesToEnricher() {
            when(aiEnricher.enrichForSurface(aggregatedData)).thenReturn(successResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            verify(aiEnricher).enrichForSurface(aggregatedData);
        }

        @Test
        @DisplayName("does NOT invoke fallbackCreator on success")
        void doesNotCallFallbackOnSuccess() {
            when(aiEnricher.enrichForSurface(aggregatedData)).thenReturn(successResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            verifyNoInteractions(fallbackCreator);
        }

        @Test
        @DisplayName("result is not marked as fallback on success")
        void resultIsNotFallback() {
            when(aiEnricher.enrichForSurface(aggregatedData)).thenReturn(successResult);

            EnrichedScanResult result = openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(result.isFallback()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // enrichWithCircuitBreaker() — fallback path
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("enrichWithCircuitBreaker() — fallback path")
    class FallbackPath {

        @Test
        @DisplayName("returns fallback result when enricher throws RuntimeException")
        void returnsFallbackOnRuntimeException() {
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException("OpenAI timeout"));
            when(fallbackCreator.createForSurface(eq(scanId), eq(SurfaceType.SSL), any()))
                    .thenReturn(fallbackResult);

            EnrichedScanResult result = openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(result).isEqualTo(fallbackResult);
        }

        @Test
        @DisplayName("invokes fallbackCreator with correct scanId, surface, and error message")
        void callsFallbackCreatorWithCorrectArgs() {
            String errorMessage = "Connection refused";
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException(errorMessage));
            when(fallbackCreator.createForSurface(scanId, SurfaceType.SSL, errorMessage))
                    .thenReturn(fallbackResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            verify(fallbackCreator).createForSurface(scanId, SurfaceType.SSL, errorMessage);
        }

        @Test
        @DisplayName("fallback result is marked as fallback")
        void fallbackResultIsMarked() {
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException("err"));
            when(fallbackCreator.createForSurface(any(), any(), any()))
                    .thenReturn(fallbackResult);

            EnrichedScanResult result = openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(result.isFallback()).isTrue();
        }

        @Test
        @DisplayName("passes null exception message to fallback when exception has no message")
        void handlesExceptionWithNullMessage() {
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException((String) null));
            when(fallbackCreator.createForSurface(scanId, SurfaceType.SSL, null))
                    .thenReturn(fallbackResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            verify(fallbackCreator).createForSurface(scanId, SurfaceType.SSL, null);
        }

        @Test
        @DisplayName("falls back on checked exception wrapped in RuntimeException")
        void fallsBackOnWrappedCheckedException() {
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException("wrapped", new Exception("root cause")));
            when(fallbackCreator.createForSurface(any(), any(), any()))
                    .thenReturn(fallbackResult);

            EnrichedScanResult result = openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(result).isEqualTo(fallbackResult);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Circuit breaker state transitions
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Circuit breaker state transitions")
    class CircuitBreakerStates {

        @Test
        @DisplayName("circuit breaker starts in CLOSED state")
        void startsInClosedState() {
            assertThat(openAiCircuitBreaker.getState()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("getState() reflects real circuit breaker state")
        void getStateReflectsRealState() {
            assertThat(openAiCircuitBreaker.getState())
                    .isEqualTo(circuitBreaker.getState().name());
        }

        @Test
        @DisplayName("circuit breaker transitions to OPEN after sufficient failures")
        void transitionsToOpenAfterFailures() {
            // Build a circuit breaker that opens after 2 failures out of 2
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(100)
                    .minimumNumberOfCalls(2)
                    .slidingWindowSize(2)
                    .build();
            CircuitBreaker fastOpenCb = CircuitBreaker.of("fast-open", config);
            OpenAiCircuitBreaker cbUnderTest =
                    new OpenAiCircuitBreaker(aiEnricher, fallbackCreator, fastOpenCb);

            EnrichedScanResult fb = EnrichedScanResult.builder().isFallback(true).build();
            when(aiEnricher.enrichForSurface(any())).thenThrow(new RuntimeException("fail"));
            when(fallbackCreator.createForSurface(any(), any(), any())).thenReturn(fb);

            cbUnderTest.enrichWithCircuitBreaker(aggregatedData);
            cbUnderTest.enrichWithCircuitBreaker(aggregatedData);

            assertThat(cbUnderTest.getState()).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("after circuit opens, subsequent calls return fallback without calling enricher")
        void openCircuitBypassesEnricher() {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(100)
                    .minimumNumberOfCalls(2)
                    .slidingWindowSize(2)
                    .build();
            CircuitBreaker fastOpenCb = CircuitBreaker.of("fast-open-2", config);
            OpenAiCircuitBreaker cbUnderTest =
                    new OpenAiCircuitBreaker(aiEnricher, fallbackCreator, fastOpenCb);

            EnrichedScanResult fb = EnrichedScanResult.builder().isFallback(true).build();
            when(aiEnricher.enrichForSurface(any())).thenThrow(new RuntimeException("fail"));
            when(fallbackCreator.createForSurface(any(), any(), any())).thenReturn(fb);

            // Force the circuit open
            cbUnderTest.enrichWithCircuitBreaker(aggregatedData);
            cbUnderTest.enrichWithCircuitBreaker(aggregatedData);
            assertThat(cbUnderTest.getState()).isEqualTo("OPEN");

            // Reset invocation count
            clearInvocations(aiEnricher);

            // Next call — circuit is OPEN, enricher must not be called
            cbUnderTest.enrichWithCircuitBreaker(aggregatedData);

            verify(aiEnricher, never()).enrichForSurface(any());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // getMetrics()
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getMetrics()")
    class GetMetrics {

        @Test
        @DisplayName("returns non-null metrics object")
        void returnsNonNullMetrics() {
            assertThat(openAiCircuitBreaker.getMetrics()).isNotNull();
        }

        @Test
        @DisplayName("metrics state matches circuit breaker state")
        void metricsStateMatchesCbState() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    openAiCircuitBreaker.getMetrics();

            assertThat(metrics.getState()).isEqualTo(circuitBreaker.getState().name());
        }

        @Test
        @DisplayName("metrics failure rate matches circuit breaker metrics")
        void metricsFailureRateMatches() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    openAiCircuitBreaker.getMetrics();

            assertThat(metrics.getFailureRate())
                    .isEqualTo(circuitBreaker.getMetrics().getFailureRate());
        }

        @Test
        @DisplayName("metrics slow calls matches circuit breaker metrics")
        void metricsSlowCallsMatch() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    openAiCircuitBreaker.getMetrics();

            assertThat(metrics.getSlowCalls())
                    .isEqualTo(circuitBreaker.getMetrics().getNumberOfSlowCalls());
        }

        @Test
        @DisplayName("metrics failed calls matches circuit breaker metrics")
        void metricsFailedCallsMatch() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    openAiCircuitBreaker.getMetrics();

            assertThat(metrics.getFailedCalls())
                    .isEqualTo(circuitBreaker.getMetrics().getNumberOfFailedCalls());
        }

        @Test
        @DisplayName("metrics successful calls matches circuit breaker metrics")
        void metricsSuccessfulCallsMatch() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    openAiCircuitBreaker.getMetrics();

            assertThat(metrics.getSuccessfulCalls())
                    .isEqualTo(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
        }

        @Test
        @DisplayName("successful call increments successfulCalls metric")
        void successfulCallIncrementsMetric() {
            when(aiEnricher.enrichForSurface(aggregatedData)).thenReturn(successResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(openAiCircuitBreaker.getMetrics().getSuccessfulCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("failed call increments failedCalls metric")
        void failedCallIncrementsMetric() {
            when(aiEnricher.enrichForSurface(aggregatedData))
                    .thenThrow(new RuntimeException("fail"));
            when(fallbackCreator.createForSurface(any(), any(), any()))
                    .thenReturn(fallbackResult);

            openAiCircuitBreaker.enrichWithCircuitBreaker(aggregatedData);

            assertThat(openAiCircuitBreaker.getMetrics().getFailedCalls()).isEqualTo(1);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CircuitBreakerMetrics inner class
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CircuitBreakerMetrics builder")
    class MetricsBuilder {

        @Test
        @DisplayName("builder sets all fields correctly")
        void builderSetsAllFields() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics metrics =
                    OpenAiCircuitBreaker.CircuitBreakerMetrics.builder()
                            .state("CLOSED")
                            .failureRate(25.0f)
                            .slowCalls(3)
                            .failedCalls(5)
                            .successfulCalls(10)
                            .build();

            assertThat(metrics.getState()).isEqualTo("CLOSED");
            assertThat(metrics.getFailureRate()).isEqualTo(25.0f);
            assertThat(metrics.getSlowCalls()).isEqualTo(3);
            assertThat(metrics.getFailedCalls()).isEqualTo(5);
            assertThat(metrics.getSuccessfulCalls()).isEqualTo(10);
        }

        @Test
        @DisplayName("two metrics instances with same values are equal (Lombok @Data)")
        void equalityByValue() {
            OpenAiCircuitBreaker.CircuitBreakerMetrics m1 =
                    OpenAiCircuitBreaker.CircuitBreakerMetrics.builder()
                            .state("OPEN").failureRate(100f).slowCalls(0)
                            .failedCalls(10).successfulCalls(0).build();

            OpenAiCircuitBreaker.CircuitBreakerMetrics m2 =
                    OpenAiCircuitBreaker.CircuitBreakerMetrics.builder()
                            .state("OPEN").failureRate(100f).slowCalls(0)
                            .failedCalls(10).successfulCalls(0).build();

            assertThat(m1).isEqualTo(m2);
        }
    }
}
