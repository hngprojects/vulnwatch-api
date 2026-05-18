package com.vulnwatch.worker.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class OpenAiCircuitBreakerConfig {

    @Value("${resilience4j.circuit-breaker.ai-enricher.failure-rate-threshold:50}")
    private float failureRateThreshold;

    @Value("${resilience4j.circuit-breaker.ai-enricher.sliding-window-size:5}")
    private int slidingWindowSize;

    @Value("${resilience4j.circuit-breaker.ai-enricher.minimum-number-of-calls:3}")
    private int minimumNumberOfCalls;

    @Value("${resilience4j.circuit-breaker.ai-enricher.wait-duration-in-open-state:30s}")
    private String waitDurationInOpenState;

    @Value("${resilience4j.circuit-breaker.ai-enricher.permitted-number-of-calls-in-half-open-state:2}")
    private int permittedNumberOfCallsInHalfOpenState;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        Duration waitDuration = Duration.parse(waitDurationInOpenState);

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitDuration)
                .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                .recordExceptions(Exception.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        log.info("CircuitBreakerRegistry initialized with config: failureRateThreshold={}, slidingWindowSize={}, waitDuration={}",
                failureRateThreshold, slidingWindowSize, waitDuration);

        return registry;
    }

    @Bean
    public CircuitBreaker aiEnricherCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker("ai-enricher");

        // Add event listener for logging state changes
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("Circuit breaker 'ai-enricher' state changed: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> log.warn("Circuit breaker 'ai-enricher' OPEN, call blocked"))
                .onError(event -> log.debug("Circuit breaker 'ai-enricher' recorded error: {}",
                        event.getElapsedDuration()));

        return circuitBreaker;
    }
}