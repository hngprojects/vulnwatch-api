package com.vulnwatch.worker.circuitbreaker;

import com.vulnwatch.worker.ai.AiEnricher;
import com.vulnwatch.worker.ai.FallbackResultCreator;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCircuitBreaker {

    private final AiEnricher aiEnricher;
    private final FallbackResultCreator fallbackCreator;
    private final CircuitBreaker circuitBreaker;

    /**
     * Enriches scan data with circuit breaker protection.
     * Returns either real AI result or fallback EnrichedScanResult.
     */
    public EnrichedScanResult enrichWithCircuitBreaker(AggregatedScanData aggregatedData) {
        UUID scanId = aggregatedData.getScanId();
        SurfaceType surface = aggregatedData.getSuccessfulResults().get(0).getSurface();

        log.debug("Calling AI enrichment for scan {} with circuit breaker (state={})",
                scanId, circuitBreaker.getState().name());

        try {
            return circuitBreaker
                    .executeSupplier(() -> aiEnricher.enrichForSurface(aggregatedData));
        } catch (Exception e) {
            log.warn("Circuit breaker fallback for scan {} surface {}: {}", scanId, surface, e.getMessage());
            return fallbackCreator.createForSurface(scanId, surface, e.getMessage());
        }
    }

    public String getState() {
        return circuitBreaker.getState().name();
    }

    public CircuitBreakerMetrics getMetrics() {
        return CircuitBreakerMetrics.builder()
                .state(circuitBreaker.getState().name())
                .failureRate(circuitBreaker.getMetrics().getFailureRate())
                .slowCalls(circuitBreaker.getMetrics().getNumberOfSlowCalls())
                .failedCalls(circuitBreaker.getMetrics().getNumberOfFailedCalls())
                .successfulCalls(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls())
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class CircuitBreakerMetrics {
        private String state;
        private Float failureRate;
        private Integer slowCalls;
        private Integer failedCalls;
        private Integer successfulCalls;
    }
}