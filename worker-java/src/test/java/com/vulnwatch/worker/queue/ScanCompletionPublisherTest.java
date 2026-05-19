package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.exception.ScanPublishException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uses a real ObjectMapper so we can assert actual JSON field values,
 * not just that serialisation was called.
 * Only RedisTemplate and ListOperations are mocked — transport layer only.
 */
@ExtendWith(MockitoExtension.class)
class ScanCompletionPublisherTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ListOperations<String, Object> listOps;

    // Real ObjectMapper — lets us assert actual message content
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private ScanCompletionPublisher publisher;
    private UUID scanId;

    @BeforeEach
    void setUp() {
        publisher = new ScanCompletionPublisher(redisTemplate, objectMapper);
        scanId = UUID.randomUUID();
        when(redisTemplate.opsForList()).thenReturn(listOps);
    }


    @Nested
    @DisplayName("publishCompletion")
    class PublishCompletion {

        @Test
        @DisplayName("pushes JSON to correct Redis key")
        void publishCompletion_pushesToCorrectKey() {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 85, 5, List.of());

            verify(listOps).leftPush(eq(RedisConfig.Keys.SCAN_RESULTS_LIST), any());
        }

        @Test
        @DisplayName("message contains correct scanId, status, score and findingCount")
        void publishCompletion_messageContainsCorrectFields() throws Exception {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 85, 5, List.of());

            Map<String, Object> message = capturePublishedMessage();
            assertThat(message.get("scanId")).isEqualTo(scanId.toString());
            assertThat(message.get("status")).isEqualTo("completed");
            assertThat(message.get("securityScore")).isEqualTo(85);
            assertThat(message.get("findingCount")).isEqualTo(5);
            assertThat(message.get("processedAt")).isNotNull();
        }

        @Test
        @DisplayName("hasFallback is false and fallbackSurfaces is empty when no fallbacks")
        void publishCompletion_noFallbacks_hasFallbackFalse() throws Exception {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 85, 5, List.of());

            Map<String, Object> message = capturePublishedMessage();
            assertThat(message.get("hasFallback")).isEqualTo(false);
            assertThat((List<?>) message.get("fallbackSurfaces")).isEmpty();
        }

        @Test
        @DisplayName("hasFallback is true and fallbackSurfaces contains the failed surfaces")
        void publishCompletion_withFallbacks_hasFallbackTrue() throws Exception {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 72, 3,
                    List.of("SSL", "HTTP_HEADERS"));

            Map<String, Object> message = capturePublishedMessage();
            assertThat(message.get("hasFallback")).isEqualTo(true);
            assertThat(message.get("fallbackSurfaces"))
                    .asList()
                    .containsExactlyInAnyOrder("SSL", "HTTP_HEADERS");
        }

        @Test
        @DisplayName("status is failed for FAILED enum value")
        void publishCompletion_partialFailureStatus_serialisedCorrectly() throws Exception {
            publisher.publishCompletion(scanId, ScanStatus.FAILED, 50, 2,
                    List.of("DNS"));

            Map<String, Object> message = capturePublishedMessage();
//            assertThat(message.get("status"))
////                    .asList()
//                    .containsExactlyInAnyOrder("partial_failure");
            assertThat(message.get("status")).isEqualTo("failed");
        }

        @Test
        @DisplayName("throws ScanPublishException when Redis push fails")
        void publishCompletion_redisFails_throwsScanPublishException() {
            doThrow(new RuntimeException("Redis connection failed"))
                    .when(listOps).leftPush(anyString(), any());

            assertThatThrownBy(() ->
                    publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 85, 5, List.of()))
                    .isInstanceOf(ScanPublishException.class)
                    .hasMessageContaining(scanId.toString());
        }
    }



    @Nested
    @DisplayName("publishScanFailed")
    class PublishScanFailed {

        @Test
        @DisplayName("pushes JSON to correct Redis key")
        void publishScanFailed_pushesToCorrectKey() {
            publisher.publishScanFailed(scanId, "No eligible scanners");

            verify(listOps).leftPush(eq(RedisConfig.Keys.SCAN_RESULTS_LIST), any());
        }

        @Test
        @DisplayName("message has status=failed, findingCount=0, securityScore=null")
        void publishScanFailed_messageHasCorrectFailureFields() throws Exception {
            publisher.publishScanFailed(scanId, "No eligible scanners");

            Map<String, Object> message = capturePublishedMessage();
            assertThat(message.get("scanId")).isEqualTo(scanId.toString());
            assertThat(message.get("status")).isEqualTo("failed");
            assertThat(message.get("findingCount")).isEqualTo(0);
            assertThat(message.get("securityScore")).isNull();
            assertThat(message.get("processedAt")).isNotNull();
        }

        @Test
        @DisplayName("throws ScanPublishException when Redis push fails")
        void publishScanFailed_redisFails_throwsScanPublishException() {
            doThrow(new RuntimeException("Redis down"))
                    .when(listOps).leftPush(anyString(), any());

            assertThatThrownBy(() ->
                    publisher.publishScanFailed(scanId, "Test error"))
                    .isInstanceOf(ScanPublishException.class)
                    .hasMessageContaining(scanId.toString());
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty fallbackSurfaces list is handled without NPE")
        void publishCompletion_emptyFallbacks_noException() {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 85, 5, List.of());
            verify(listOps).leftPush(anyString(), any());
        }

        @Test
        @DisplayName("zero score and zero findings are serialised correctly")
        void publishCompletion_zeroScoreAndFindings_serialisedCorrectly() throws Exception {
            publisher.publishCompletion(scanId, ScanStatus.COMPLETED, 0, 0, List.of());

            Map<String, Object> message = capturePublishedMessage();
            assertThat(message.get("securityScore")).isEqualTo(0);
            assertThat(message.get("findingCount")).isEqualTo(0);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Captures the JSON string pushed to Redis and parses it into a Map
     * so we can assert individual field values cleanly.
     */
    private Map<String, Object> capturePublishedMessage() throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(listOps).leftPush(anyString(), captor.capture());
        String json = (String) captor.getValue();
        return objectMapper.readValue(json, new TypeReference<>() {});
    }
}