package com.vulnwatch.worker.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.exception.SurfacePublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Design notes:
 *
 * 1. HashOperations is held as a separate @Mock field (not obtained via
 *    redisTemplate.opsForHash() inside verify()). Calling opsForHash() inside
 *    a verify() call counts as a real interaction on the mock, which confuses
 *    Mockito and produces false "unnecessary stubbing" errors.
 *
 * 2. Tests that don't touch hash operations don't stub opsForHash() at all.
 *    stubHashOps() is a helper called only where needed.
 *
 * 3. publish_whenRedisStreamAddFails: the production pushToStream() only wraps
 *    JsonProcessingException in SurfacePublishException. A raw RuntimeException
 *    from streamOps.add() propagates unwrapped — the test asserts exactly that.
 *
 * 4. publish_whenOffloadFails: a RuntimeException from opsForHash().put() is NOT
 *    caught by the JsonProcessingException handler in offloadRawDataIfPresent(),
 *    so it propagates out of publish() — the test asserts exactly that.
 */
@ExtendWith(MockitoExtension.class)
class SurfaceEventPublisherTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ObjectMapper objectMapper;

    private SurfaceEventPublisher publisher;

    private UUID scanId;
    private SurfaceType surface;
    private SurfaceResultEvent successEvent;

    @BeforeEach
    void setUp() {
        publisher = new SurfaceEventPublisher(redisTemplate, objectMapper);
        scanId = UUID.randomUUID();
        surface = SurfaceType.DNS;
        // No rawData — offloadRawDataIfPresent returns immediately, so tests that
        // only care about pushToStream don't need to stub writeValueAsString(Map)
        // or opsForHash, and opsForStream is the only Redis op that fires.
        successEvent = SurfaceResultEvent.success(scanId, surface, null, 0);
    }

    // Call in tests that reach pushToStream (stream write path)
    private void stubStreamOps() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
    }

    // Call in tests that reach offloadRawDataIfPresent with non-empty rawData
    private void stubHashOps() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    // ==================== PUBLISH ====================

    @Test
    void publish_whenEventHasNoRawData_publishesDirectlyWithoutTouchingHash() throws Exception {
        stubStreamOps();
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, null, 0);
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenReturn("{\"scanId\":\"" + scanId + "\"}");

        publisher.publish(event);

        verify(streamOps).add(any(MapRecord.class));
        // opsForHash() must never be called — don't even stub it
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    void publish_whenEventHasRawData_offloadsToHashAndPublishesToStream() throws Exception {
        stubStreamOps();
        stubHashOps();
        Map<String, Object> rawData = Map.of("record_a", "93.184.216.34");
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, rawData, 0);

        String rawDataJson = "{\"record_a\":\"93.184.216.34\"}";
        when(objectMapper.writeValueAsString(rawData)).thenReturn(rawDataJson);
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenReturn("{\"scanId\":\"" + scanId + "\",\"rawDataKey\":\"scan:" + scanId + ":raw:DNS\"}");

        publisher.publish(event);

        verify(hashOps).put(eq("scan:" + scanId + ":raw:DNS"), eq("data"), eq(rawDataJson));
        verify(redisTemplate).expire(eq("scan:" + scanId + ":raw:DNS"), eq(Duration.ofHours(24)));
        verify(streamOps).add(any(MapRecord.class));
    }

    @Test
    void publish_whenSerializationFails_throwsSurfacePublishException() throws Exception {
        // successEvent has no rawData — writeValueAsString throws in pushToStream before
        // opsForStream() is ever called, so no stream stub needed
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenThrow(new JsonProcessingException("Serialization error") {});

        assertThatThrownBy(() -> publisher.publish(successEvent))
                .isInstanceOf(SurfacePublishException.class)
                .hasMessageContaining("Failed to serialise surface event for scan " + scanId);
    }

    @Test
    void publish_whenRedisStreamAddThrowsRuntimeException_propagatesUnwrapped() throws Exception {
        stubStreamOps();
        // successEvent has no rawData — writeValueAsString is called once with a SurfaceResultEvent
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenReturn("{\"scanId\":\"" + scanId + "\"}");
        doThrow(new RuntimeException("Redis connection failed")).when(streamOps).add(any(MapRecord.class));

        assertThatThrownBy(() -> publisher.publish(successEvent))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis connection failed");
    }

    @Test
    void publish_whenOffloadHashPutThrows_propagatesUnwrapped() throws Exception {
        // RuntimeException from opsForHash().put() is NOT caught by the
        // JsonProcessingException handler — it escapes publish() before reaching the stream.
        stubHashOps();

        Map<String, Object> rawData = Map.of("record_a", "93.184.216.34");
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, rawData, 0);
        String rawDataJson = "{\"record_a\":\"93.184.216.34\"}";
        when(objectMapper.writeValueAsString(rawData)).thenReturn(rawDataJson);
        doThrow(new RuntimeException("Redis error")).when(hashOps).put(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis error");
    }

    // ==================== PUBLISH SCAN FAILED ====================

    @Test
    void publishScanFailed_createsAndPublishesFailureEvent() throws Exception {
        stubStreamOps();
        String reason = "No eligible scanners available";
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenReturn("{\"scanId\":\"" + scanId + "\",\"success\":false}");

        publisher.publishScanFailed(scanId, reason);

        verify(streamOps).add(any(MapRecord.class));
    }

    // ==================== OFFLOAD RAW DATA ====================

    @Test
    void offloadRawDataIfPresent_whenRawDataNull_returnsSameEvent() {
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, null, 0);

        SurfaceResultEvent result = ReflectionTestUtils.invokeMethod(
                publisher, "offloadRawDataIfPresent", event);

        assertThat(result).isSameAs(event);
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    void offloadRawDataIfPresent_whenRawDataEmpty_returnsSameEvent() {
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, Map.of(), 0);

        SurfaceResultEvent result = ReflectionTestUtils.invokeMethod(
                publisher, "offloadRawDataIfPresent", event);

        assertThat(result).isSameAs(event);
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    void offloadRawDataIfPresent_whenSerializationFails_returnsOriginalEventWithRawDataIntact() throws Exception {
        Map<String, Object> rawData = Map.of("record_a", "93.184.216.34");
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, rawData, 0);
        // No stubHashOps() — serialization throws before opsForHash() is ever called
        when(objectMapper.writeValueAsString(rawData))
                .thenThrow(new JsonProcessingException("Serialization error") {});

        SurfaceResultEvent result = ReflectionTestUtils.invokeMethod(
                publisher, "offloadRawDataIfPresent", event);

        assertThat(result).isSameAs(event);
        assertThat(result.getRawData()).isEqualTo(rawData);
        assertThat(result.getRawDataKey()).isNull();
        // opsForHash() was never called — verified implicitly since it was never stubbed
    }

    @Test
    void offloadRawDataIfPresent_whenSuccessful_returnsLightweightCopyWithRawDataKey() throws Exception {
        Map<String, Object> rawData = Map.of("record_a", "93.184.216.34");
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, rawData, 0);
        stubHashOps();

        String rawDataJson = "{\"record_a\":\"93.184.216.34\"}";
        when(objectMapper.writeValueAsString(rawData)).thenReturn(rawDataJson);

        SurfaceResultEvent result = ReflectionTestUtils.invokeMethod(
                publisher, "offloadRawDataIfPresent", event);

        assertThat(result.getRawData()).isNull();
        assertThat(result.getRawDataKey()).isEqualTo("scan:" + scanId + ":raw:DNS");
        verify(hashOps).put(eq("scan:" + scanId + ":raw:DNS"), eq("data"), eq(rawDataJson));
        verify(redisTemplate).expire(eq("scan:" + scanId + ":raw:DNS"), eq(Duration.ofHours(24)));
    }

    // ==================== PUSH TO STREAM ====================

    @Test
    void pushToStream_addsCorrectlyShapedRecordToStream() throws Exception {
        stubStreamOps();
        String json = "{\"scanId\":\"" + scanId + "\",\"surface\":\"DNS\",\"success\":true}";
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class))).thenReturn(json);

        ReflectionTestUtils.invokeMethod(publisher, "pushToStream", successEvent);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());

        MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo(RedisConfig.Keys.SURFACE_RESULT_STREAM);
        assertThat(record.getValue().get("event")).isEqualTo(json);
    }

    @Test
    void pushToStream_whenSerializationFails_throwsSurfacePublishException() throws Exception {
        // No stubStreamOps() — writeValueAsString throws before opsForStream() is called
        when(objectMapper.writeValueAsString(any(SurfaceResultEvent.class)))
                .thenThrow(new JsonProcessingException("Serialization error") {});

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                publisher, "pushToStream", successEvent))
                .isInstanceOf(SurfacePublishException.class)
                .hasMessageContaining("Failed to serialise surface event for scan " + scanId);
    }



    @Test
    void publish_rawDataIsNullOnEventSentToStream() throws Exception {
        stubStreamOps();
        stubHashOps();
        Map<String, Object> rawData = Map.of("record_a", "93.184.216.34");
        SurfaceResultEvent event = SurfaceResultEvent.success(scanId, surface, rawData, 0);

        String rawDataJson = "{\"record_a\":\"93.184.216.34\"}";
        when(objectMapper.writeValueAsString(rawData)).thenReturn(rawDataJson);

        // Capture the event object passed to writeValueAsString for the stream
        when(objectMapper.writeValueAsString(argThat(
                arg -> arg instanceof SurfaceResultEvent
                        && ((SurfaceResultEvent) arg).getRawData() == null)))
                .thenReturn("{\"scanId\":\"" + scanId + "\",\"rawDataKey\":\"scan:" + scanId + ":raw:DNS\"}");

        publisher.publish(event);

        // Verify streamOps.add was called — the argThat stub above only matches
        // if rawData was null at serialisation time, so if add() was called we know
        // the lightweight copy was what reached the stream.
        verify(streamOps).add(any(MapRecord.class));
    }
}