package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeadLetterQueueHandler")
class DeadLetterQueueHandlerTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ListOperations<String, Object> listOps;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());


    private DeadLetterQueueHandler handler;



    private static final String DLQ_KEY = RedisConfig.Keys.DEAD_LETTER_LIST;
    private UUID scanId;
    private SurfaceResultEvent event;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        handler = new DeadLetterQueueHandler(redisTemplate, objectMapper);

        scanId = UUID.randomUUID();
        event = SurfaceResultEvent.failure(scanId, SurfaceType.DNS, "connection timeout", 2);
    }

    // ─────────────────────────────────────────────────────────────
    // moveToDeadLetter
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("moveToDeadLetter()")
    class MoveToDeadLetter {

        @Test
        @DisplayName("pushes serialized entry to DLQ list")
        void pushesToDlqList() {
            handler.moveToDeadLetter(event);

            verify(listOps).leftPush(eq(DLQ_KEY), argThat(raw -> {
                String json = raw.toString();
                return json.contains(scanId.toString())
                        && json.contains("DNS")
                        && json.contains("connection timeout");
            }));
        }

        @Test
        @DisplayName("does not throw when Redis fails — swallows exception silently")
        void swallowsRedisException() {
            when(listOps.leftPush(any(), any())).thenThrow(new RuntimeException("Redis down"));

            // Should not throw
            handler.moveToDeadLetter(event);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // listDeadLetters
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listDeadLetters()")
    class ListDeadLetters {

        @Test
        @DisplayName("returns empty list when Redis returns null")
        void returnsEmptyWhenNull() {
            when(listOps.range(DLQ_KEY, 0, -1)).thenReturn(null);

            assertThat(handler.listDeadLetters()).isEmpty();
        }

        @Test
        @DisplayName("parses valid JSON entries into maps")
        void parsesValidEntries() throws Exception {
            String entry = objectMapper.writeValueAsString(
                    Map.of("scanId", scanId.toString(), "surface", "DNS"));

            when(listOps.range(DLQ_KEY, 0, -1)).thenReturn(List.of(entry));

            List<Map<String, Object>> result = handler.listDeadLetters();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst()).containsKey("scanId");
        }

        @Test
        @DisplayName("filters out unparseable entries silently")
        void filtersUnparseableEntries() {
            when(listOps.range(DLQ_KEY, 0, -1)).thenReturn(List.of("not-json"));

            assertThat(handler.listDeadLetters()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // getDeadLetter
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getDeadLetter()")
    class GetDeadLetter {

        @Test
        @DisplayName("returns null when index does not exist")
        void returnsNullWhenMissing() {
            when(listOps.index(DLQ_KEY, 5)).thenReturn(null);

            assertThat(handler.getDeadLetter(5)).isNull();
        }

        @Test
        @DisplayName("parses and returns entry at given index")
        void returnsEntryAtIndex() throws Exception {
            String json = objectMapper.writeValueAsString(Map.of("scanId", scanId.toString()));
            when(listOps.index(DLQ_KEY, 0)).thenReturn(json);

            Map<String, Object> result = handler.getDeadLetter(0);

            assertThat(result).containsKey("scanId");
        }

        @Test
        @DisplayName("returns null when entry JSON is invalid")
        void returnsNullOnBadJson() {
            when(listOps.index(DLQ_KEY, 0)).thenReturn("not-json");

            assertThat(handler.getDeadLetter(0)).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // deleteDeadLetter
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("deleteDeadLetter()")
    class DeleteDeadLetter {

        @Test
        @DisplayName("removes entry from list when found")
        void removesEntryWhenFound() {
            String entry = "some-entry";
            when(listOps.index(DLQ_KEY, 0)).thenReturn(entry);

            handler.deleteDeadLetter(0);

            verify(listOps).remove(DLQ_KEY, 1, entry);
        }

        @Test
        @DisplayName("does nothing when index does not exist")
        void doesNothingWhenMissing() {
            when(listOps.index(DLQ_KEY, 99)).thenReturn(null);

            handler.deleteDeadLetter(99);

            verify(listOps, never()).remove(any(), anyLong(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // replayDeadLetter
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("replayDeadLetter()")
    class ReplayDeadLetter {

        @Test
        @DisplayName("returns false when index does not exist")
        void returnsFalseWhenMissing() {
            when(listOps.index(DLQ_KEY, 0)).thenReturn(null);

            assertThat(handler.replayDeadLetter(0)).isFalse();
        }

        @Test
        @DisplayName("publishes event to stream and removes from DLQ on success")
        void publishesAndRemovesOnSuccess() throws Exception {
            when(redisTemplate.opsForStream()).thenReturn(streamOps);

            String dlqJson = buildDlqJson(event);
            when(listOps.index(DLQ_KEY, 0)).thenReturn(dlqJson);

            boolean result = handler.replayDeadLetter(0);

            assertThat(result).isTrue();
            verify(streamOps).add(any());
            verify(listOps).remove(eq(DLQ_KEY), eq(1L), eq(dlqJson));
        }

        @Test
        @DisplayName("resets attempt to 0 when replaying")
        void resetsAttemptToZero() throws Exception {
            when(redisTemplate.opsForStream()).thenReturn(streamOps);

            // Original event has attempt=2
            String dlqJson = buildDlqJson(event);
            when(listOps.index(DLQ_KEY, 0)).thenReturn(dlqJson);

            handler.replayDeadLetter(0);

            verify(streamOps).add(argThat(record -> {
                // The published stream record body should contain attempt:0
                String body = record.toString();
                return body.contains("\"attempt\":0");
            }));
        }

        @Test
        @DisplayName("returns false when DLQ entry JSON is malformed")
        void returnsFalseOnBadJson() {
            when(listOps.index(DLQ_KEY, 0)).thenReturn("not-json");

            assertThat(handler.replayDeadLetter(0)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // replayAllDeadLetters
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("replayAllDeadLetters()")
    class ReplayAll {

        @Test
        @DisplayName("returns 0 when DLQ is empty")
        void returnsZeroWhenEmpty() {
            when(listOps.size(DLQ_KEY)).thenReturn(0L);

            assertThat(handler.replayAllDeadLetters()).isZero();
        }

        @Test
        @DisplayName("returns 0 when size is null")
        void returnsZeroWhenNull() {
            when(listOps.size(DLQ_KEY)).thenReturn(null);

            assertThat(handler.replayAllDeadLetters()).isZero();
        }

        @Test
        @DisplayName("replays N entries and returns count")
        void replaysAllAndReturnsCount() throws Exception {
            when(redisTemplate.opsForStream()).thenReturn(streamOps);

            String dlqJson = buildDlqJson(event);
            when(listOps.size(DLQ_KEY)).thenReturn(2L);
            when(listOps.index(DLQ_KEY, 0)).thenReturn(dlqJson);

            int replayed = handler.replayAllDeadLetters();

            assertThat(replayed).isEqualTo(2);
            verify(streamOps, times(2)).add(any());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String buildDlqJson(SurfaceResultEvent event) throws Exception {
        String eventJson = objectMapper.writeValueAsString(event);
        Map<String, Object> entry = Map.of(
                "scanId", event.getScanId().toString(),
                "surface", event.getSurface().name(),
                "attempt", event.getAttempt(),
                "errorMessage", event.getErrorMessage() != null ? event.getErrorMessage() : "",
                "failedAt", "2026-01-01T00:00:00Z",
                "rawDataKey", "",
                "eventJson", eventJson
        );
        return objectMapper.writeValueAsString(entry);
    }
}
