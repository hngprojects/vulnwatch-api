package com.vulnwatch.worker.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.ai.FallbackResultCreator;
import com.vulnwatch.worker.ai.ScoreCalculator;
import com.vulnwatch.worker.circuitbreaker.OpenAiCircuitBreaker;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.models.AggregatedScanData;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import com.vulnwatch.worker.queue.DeadLetterQueueHandler;
import com.vulnwatch.worker.queue.ScanCompletionPublisher;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.repository.ScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResultConsumerTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private SurfaceStateManager stateManager;
    @Mock private OpenAiCircuitBreaker circuitBreaker;
    @Mock private ScanCompletionPublisher completionPublisher;
    @Mock private DeadLetterQueueHandler dlqHandler;
    @Mock private FindingRepository findingRepository;
    @Mock private ScanRepository scanRepository;
    @Mock private ScoreCalculator scoreCalculator;
    @Mock private FallbackResultCreator fallbackCreator;
    @Mock private ExecutorService consumerExecutor;
    @Mock private StreamOperations<String, Object, Object> streamOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, Object> setOps;
    @Mock private ZSetOperations<String, Object> zSetOps;

    private ResultConsumer resultConsumer;

    private UUID scanId;
    private SurfaceType surface;
    private SurfaceResultEvent successEvent;
    private SurfaceResultEvent failureEvent;
    private Scan scan;
    private EnrichedScanResult enrichedResult;
    private List<Finding> findings;

    @BeforeEach
    void setUp() {
        resultConsumer = new ResultConsumer(
                redisTemplate, objectMapper, stateManager, circuitBreaker,
                completionPublisher, dlqHandler, findingRepository, scanRepository,
                scoreCalculator, fallbackCreator, consumerExecutor
        );

        ReflectionTestUtils.setField(resultConsumer, "maxRetries", 3);
        ReflectionTestUtils.setField(resultConsumer, "shutdownTimeoutSeconds", 15);
        ReflectionTestUtils.setField(resultConsumer, "running", new AtomicBoolean(false));

        scanId = UUID.randomUUID();
        surface = SurfaceType.DNS;

        successEvent = SurfaceResultEvent.success(scanId, surface, Map.of("result", "ok"), 0);
        failureEvent = SurfaceResultEvent.failure(scanId, surface, "Connection timeout", 0);

        scan = Scan.builder()
                .id(scanId)
                .userId(UUID.randomUUID())
                .status(ScanStatus.RUNNING)
                .targetType(TargetType.DOMAIN)
                .build();

        findings = List.of(
                Finding.builder()
                        .id(UUID.randomUUID())
                        .scanId(scanId)
                        .surface(surface)
                        .build()
        );

        enrichedResult = EnrichedScanResult.builder()
                .scanId(scanId)
                .surface(surface)
                .securityScore(85)
                .findings(findings)
                .isFallback(false)
                .processedAt(Instant.now())
                .build();

        // These four delegate the four Redis ops categories used throughout the suite.
        // Not every test exercises all four (e.g. determineScanStatus touches none;
        // isAlreadyProcessed only touches setOps). lenient() is the correct Mockito
        // pattern for shared infrastructure stubs: it disables the strict
        // "was-this-stub-consumed?" check per-test for these specific stubs only,
        // without loosening strictness on anything else.
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    // ─────────────────────────── helpers ────────────────────────────

    /**
     * Stubs every dependency that checkAndPublishCompletion needs once
     * stateManager.isAllTerminal returns true. Call this in any test that
     * drives handleSuccess / handleFailure all the way through to completion.
     */
    private void stubTerminalCompletion(Map<Object, Object> scoreMap) {
        when(stateManager.isAllTerminal(scanId)).thenReturn(true);
        when(stateManager.getFailedSurfaces(scanId)).thenReturn(List.of());
        when(stateManager.getSuccessfulSurfaces(scanId)).thenReturn(List.of(surface.name()));
        when(findingRepository.countByScanId(scanId)).thenReturn((long) findings.size());
        when(hashOps.entries("scan:" + scanId + ":scores")).thenReturn(scoreMap);
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
    }

    // ==================== PROCESS RECORD TESTS ====================

    @Test
    void processRecord_whenEventIsSuccess_shouldInvokeHandleSuccessPath() throws Exception {
        // given
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("event", "{\"scanId\":\"" + scanId + "\"}"));
        when(objectMapper.readValue(anyString(), eq(SurfaceResultEvent.class))).thenReturn(successEvent);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        when(circuitBreaker.enrichWithCircuitBreaker(any(AggregatedScanData.class))).thenReturn(enrichedResult);
        when(scoreCalculator.calculateFromDbFindings(findings)).thenReturn(85);
        stubTerminalCompletion(Map.of("DNS", "85"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "processRecord", record);

        // then
        verify(stateManager).updateSuccess(scanId, surface);
        verify(findingRepository).saveAll(findings);
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.COMPLETED), eq(85), eq(findings.size()), anyList());
    }

    @Test
    void processRecord_whenEventIsFailure_shouldInvokeHandleFailurePath() throws Exception {
        // given
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("event", "{\"scanId\":\"" + scanId + "\"}"));
        when(objectMapper.readValue(anyString(), eq(SurfaceResultEvent.class))).thenReturn(failureEvent);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        // writeToRetryZset calls objectMapper.writeValueAsString — must be stubbed or it
        // falls through to the real ObjectMapper and the internal catch swallows it silently
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"scanId\":\"" + scanId + "\"}");

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "processRecord", record);

        // then — attempt 0 + 1 = 1, still within maxRetries(3)
        verify(stateManager).updateRetrying(eq(scanId), eq(surface), eq(1), anyString());
        verify(zSetOps).add(eq(RedisConfig.Keys.RETRY_ZSET), anyString(), anyDouble());
    }

    @Test
    void processRecord_whenDuplicateEvent_shouldSkipAllProcessing() throws Exception {
        // given
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("event", "{\"scanId\":\"" + scanId + "\"}"));
        when(objectMapper.readValue(anyString(), eq(SurfaceResultEvent.class))).thenReturn(successEvent);
        when(setOps.add(anyString(), anyString())).thenReturn(0L); // already seen

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "processRecord", record);

        // then — nothing downstream should be touched
        verify(stateManager, never()).updateSuccess(any(), any());
        verify(stateManager, never()).updateRetrying(any(), any(), anyInt(), any());
        verify(circuitBreaker, never()).enrichWithCircuitBreaker(any());
    }

    @Test
    void processRecord_whenNoEventField_shouldReturnEarlyWithoutTouchingAnything() {
        // given — getValue returns a map with no "event" key
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of());

        // when — must not throw
        ReflectionTestUtils.invokeMethod(resultConsumer, "processRecord", record);

        // then — we return before objectMapper is ever consulted
        verifyNoInteractions(objectMapper);
        verifyNoInteractions(stateManager);
        verifyNoInteractions(circuitBreaker);
    }

    @Test
    void processRecord_whenDeserializationFails_shouldAbortGracefully() throws Exception {
        // given
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("event", "not-valid-json"));
        when(objectMapper.readValue(anyString(), eq(SurfaceResultEvent.class)))
                .thenThrow(new RuntimeException("Deserialization error"));

        // when — exception must not propagate out of processRecord
        ReflectionTestUtils.invokeMethod(resultConsumer, "processRecord", record);

        // then — stateManager must never be reached
        verify(stateManager, never()).updateSuccess(any(), any());
        verify(stateManager, never()).updateRetrying(any(), any(), anyInt(), any());
    }

    // ==================== HANDLE SUCCESS TESTS ====================

    @Test
    void handleSuccess_shouldDeleteStaleFindingsAndPersistNewOnes() {
        // given
        when(circuitBreaker.enrichWithCircuitBreaker(any(AggregatedScanData.class))).thenReturn(enrichedResult);
        when(scoreCalculator.calculateFromDbFindings(findings)).thenReturn(85);
        stubTerminalCompletion(Map.of("DNS", "85"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleSuccess", successEvent);

        // then — stale findings for this surface must be deleted before saving new ones
        verify(findingRepository).deleteByScanIdAndSurface(scanId, surface);
        verify(stateManager).updateSuccess(scanId, surface);
        verify(findingRepository).saveAll(findings);
        verify(hashOps).put(eq("scan:" + scanId + ":scores"), eq("DNS"), eq("85"));
    }

    @Test
    void handleSuccess_whenAiReturnsFallback_shouldPassFallbackSurfaceListToPublisher() {
        // given
        EnrichedScanResult fallbackResult = EnrichedScanResult.builder()
                .scanId(scanId).surface(surface).findings(findings)
                .isFallback(true).fallbackReason("AI unavailable").build();
        when(circuitBreaker.enrichWithCircuitBreaker(any(AggregatedScanData.class))).thenReturn(fallbackResult);
        when(scoreCalculator.calculateFromDbFindings(findings)).thenReturn(50);
        stubTerminalCompletion(Map.of("DNS", "50"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleSuccess", successEvent);

        // then — checkAndPublishCompletion calls fallbackSurfacesMap.remove(scanId) before
        // returning, so asserting the map directly would always see it empty.
        // Instead verify the publisher received the non-empty list, which proves the
        // populate→consume round-trip worked correctly end-to-end.
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.COMPLETED), anyInt(), anyInt(),
                argThat(list -> list != null && list.contains(surface.name())));
    }

    @Test
    void handleSuccess_whenAiDoesNotFallback_shouldPassEmptyFallbackListToPublisher() {
        // given — enrichedResult has isFallback=false (wired in @BeforeEach)
        when(circuitBreaker.enrichWithCircuitBreaker(any(AggregatedScanData.class))).thenReturn(enrichedResult);
        when(scoreCalculator.calculateFromDbFindings(findings)).thenReturn(85);
        stubTerminalCompletion(Map.of("DNS", "85"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleSuccess", successEvent);

        // then — fallback list must be empty
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.COMPLETED), anyInt(), anyInt(),
                argThat(List::isEmpty));
    }

    @Test
    void handleSuccess_whenNoFindings_shouldNotCallSaveAll() {
        // given
        EnrichedScanResult emptyResult = EnrichedScanResult.builder()
                .scanId(scanId).surface(surface).findings(List.of()).isFallback(false).build();
        when(circuitBreaker.enrichWithCircuitBreaker(any(AggregatedScanData.class))).thenReturn(emptyResult);
        when(scoreCalculator.calculateFromDbFindings(List.of())).thenReturn(100);
        stubTerminalCompletion(Map.of("DNS", "100"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleSuccess", successEvent);

        // then — saveAll must never be called for an empty findings list
        verify(findingRepository, never()).saveAll(any());
        // but the score must still be accumulated
        verify(hashOps).put(eq("scan:" + scanId + ":scores"), eq("DNS"), eq("100"));
    }

    // ==================== HANDLE FAILURE TESTS ====================

    @Test
    void handleFailure_whenRetriesRemaining_shouldScheduleRetryAndNotMoveToDLQ() throws Exception {
        // given — attempt=0 → nextAttempt=1, within maxRetries(3)
        SurfaceResultEvent event = SurfaceResultEvent.failure(scanId, surface, "Timeout", 0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"retry\":true}");

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleFailure", event);

        // then
        verify(stateManager).updateRetrying(eq(scanId), eq(surface), eq(1), eq("Timeout"));
        verify(hashOps).putAll(anyString(), anyMap());
        verify(zSetOps).add(eq(RedisConfig.Keys.RETRY_ZSET), anyString(), anyDouble());
        // accumulateSurfaceScore is NOT called in the retry branch
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
        // DLQ and failure finding must not be touched
        verify(dlqHandler, never()).moveToDeadLetter(any());
        verify(findingRepository, never()).save(any());
        // completion must not be published in the retry branch
        verify(completionPublisher, never()).publishCompletion(any(), any(), anyInt(), anyInt(), anyList());
    }

    @Test
    void handleFailure_whenMaxRetriesExceeded_shouldPermanentlyFailAndMoveToDLQ() {
        // given — attempt=3 → nextAttempt=4, exceeds maxRetries(3)
        SurfaceResultEvent event = SurfaceResultEvent.failure(scanId, surface, "Timeout", 3);
        Finding failureFinding = Finding.builder().id(UUID.randomUUID()).build();
        when(fallbackCreator.createScannerFailureFinding(scanId, surface, "Timeout"))
                .thenReturn(failureFinding);
        stubTerminalCompletion(Map.of("DNS", "50"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "handleFailure", event);

        // then
        verify(stateManager).updatePermanentlyFailed(scanId, surface, "Timeout");
        verify(findingRepository).save(failureFinding);
        verify(dlqHandler).moveToDeadLetter(event);
        // neutral score of 50 is stored for the permanently-failed surface
        verify(hashOps).put(eq("scan:" + scanId + ":scores"), eq("DNS"), eq("50"));
        // completion must still fire after permanent failure
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.COMPLETED), anyInt(), anyInt(), anyList());
        // retry queue must NOT be touched in the DLQ branch
        verify(zSetOps, never()).add(eq(RedisConfig.Keys.RETRY_ZSET), anyString(), anyDouble());
    }

    // ==================== SCORE CALCULATION TESTS ====================

    @Test
    void accumulateSurfaceScore_shouldStoreScoreAndSetTtl() {
        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "accumulateSurfaceScore", scanId, surface, 85);

        // then
        verify(hashOps).put(eq("scan:" + scanId + ":scores"), eq("DNS"), eq("85"));
        verify(redisTemplate).expire(eq("scan:" + scanId + ":scores"), eq(Duration.ofHours(24)));
    }

    @Test
    void calculateOverallScore_shouldReturnAverageOfAllSurfaceScores() {
        // given — (85 + 75 + 95) / 3 = 85
        when(hashOps.entries("scan:" + scanId + ":scores"))
                .thenReturn(Map.of("DNS", "85", "SSL", "75", "HTTP", "95"));

        // when
        int result = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateOverallScore", scanId);

        // then
        assertThat(result).isEqualTo(85);
    }

    @Test
    void calculateOverallScore_whenNoScores_shouldReturnZero() {
        // given
        when(hashOps.entries("scan:" + scanId + ":scores")).thenReturn(Map.of());

        // when
        int result = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateOverallScore", scanId);

        // then
        assertThat(result).isEqualTo(0);
    }

    @Test
    void calculateOverallScore_whenOneValueIsInvalid_shouldSkipItAndAverageTheRest() {
        // given — valid: 85 + 95 = 180 / 2 = 90; "invalid" is skipped
        when(hashOps.entries("scan:" + scanId + ":scores"))
                .thenReturn(Map.of("DNS", "85", "SSL", "invalid", "HTTP", "95"));

        // when
        int result = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateOverallScore", scanId);

        // then
        assertThat(result).isEqualTo(90);
    }

    // ==================== COMPLETION TESTS ====================

    @Test
    void checkAndPublishCompletion_whenNotAllSurfacesTerminal_shouldDoNothing() {
        // given
        when(stateManager.isAllTerminal(scanId)).thenReturn(false);

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "checkAndPublishCompletion", scanId);

        // then
        verify(completionPublisher, never()).publishCompletion(any(), any(), anyInt(), anyInt(), anyList());
        verify(scanRepository, never()).findById(any());
    }

    @Test
    void checkAndPublishCompletion_whenAllSucceeded_shouldPublishCompletedWithCorrectScore() {
        // given — two surfaces, scores 85 + 75 = 160 / 2 = 80
        when(stateManager.isAllTerminal(scanId)).thenReturn(true);
        when(stateManager.getFailedSurfaces(scanId)).thenReturn(List.of());
        when(stateManager.getSuccessfulSurfaces(scanId)).thenReturn(List.of("DNS", "SSL"));
        when(findingRepository.countByScanId(scanId)).thenReturn(5L);
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(hashOps.entries("scan:" + scanId + ":scores"))
                .thenReturn(Map.of("DNS", "85", "SSL", "75"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "checkAndPublishCompletion", scanId);

        // then
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.COMPLETED), eq(80), eq(5), anyList());
        verify(scanRepository).save(argThat(s -> s.getSecurityScore() == 80));
        verify(redisTemplate).delete("scan:" + scanId + ":scores");
    }

    @Test
    void checkAndPublishCompletion_whenAllFailed_shouldPublishFailedStatus() {
        // given — no surfaces succeeded
        when(stateManager.isAllTerminal(scanId)).thenReturn(true);
        when(stateManager.getFailedSurfaces(scanId)).thenReturn(List.of("DNS", "SSL"));
        when(stateManager.getSuccessfulSurfaces(scanId)).thenReturn(List.of());
        when(findingRepository.countByScanId(scanId)).thenReturn(0L);
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(hashOps.entries("scan:" + scanId + ":scores")).thenReturn(Map.of());

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "checkAndPublishCompletion", scanId);

        // then
        verify(completionPublisher).publishCompletion(
                eq(scanId), eq(ScanStatus.FAILED), eq(0), eq(0), anyList());
    }

    @Test
    void checkAndPublishCompletion_shouldAlwaysCleanUpScoresKey() {
        // given
        when(stateManager.isAllTerminal(scanId)).thenReturn(true);
        when(stateManager.getFailedSurfaces(scanId)).thenReturn(List.of());
        when(stateManager.getSuccessfulSurfaces(scanId)).thenReturn(List.of("DNS"));
        when(findingRepository.countByScanId(scanId)).thenReturn(0L);
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(hashOps.entries("scan:" + scanId + ":scores")).thenReturn(Map.of("DNS", "80"));

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "checkAndPublishCompletion", scanId);

        // then — scores key must be deleted regardless of overall status
        verify(redisTemplate).delete("scan:" + scanId + ":scores");
    }

    // ==================== SCAN STATUS DETERMINATION ====================

    @Test
    void determineScanStatus_whenAtLeastOneSurfaceSucceeded_shouldReturnCompleted() {
        // when
        ScanStatus status = ReflectionTestUtils.invokeMethod(
                resultConsumer, "determineScanStatus",
                List.of("DNS", "SSL"), List.of("HTTP"));

        // then — partial success still counts as COMPLETED
        assertThat(status).isEqualTo(ScanStatus.COMPLETED);
    }

    @Test
    void determineScanStatus_whenNoSurfaceSucceeded_shouldReturnFailed() {
        // when
        ScanStatus status = ReflectionTestUtils.invokeMethod(
                resultConsumer, "determineScanStatus",
                List.of(), List.of("DNS", "SSL"));

        // then
        assertThat(status).isEqualTo(ScanStatus.FAILED);
    }

    // ==================== IDEMPOTENCY TESTS ====================

    @Test
    void isAlreadyProcessed_whenFirstTime_shouldReturnFalseAndRegisterTtl() {
        // given
        when(setOps.add(anyString(), anyString())).thenReturn(1L);

        // when
        boolean result = ReflectionTestUtils.invokeMethod(resultConsumer, "isAlreadyProcessed", successEvent);

        // then
        assertThat(result).isFalse();
        verify(redisTemplate).expire(anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void isAlreadyProcessed_whenAlreadySeen_shouldReturnTrueAndNotResetTtl() {
        // given
        when(setOps.add(anyString(), anyString())).thenReturn(0L);

        // when
        boolean result = ReflectionTestUtils.invokeMethod(resultConsumer, "isAlreadyProcessed", successEvent);

        // then
        assertThat(result).isTrue();
        // TTL must not be reset for a duplicate — the original TTL remains intact
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    // ==================== RESOLVE RAW DATA TESTS ====================

    @Test
    void resolveRawData_whenEventCarriesInlineRawData_shouldReturnItWithoutConsultingRedis() {
        // given — successEvent was built with inline rawData = {"result":"ok"}
        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                ReflectionTestUtils.invokeMethod(resultConsumer, "resolveRawData", successEvent);

        // then
        assertThat(result).containsEntry("result", "ok");
        verify(hashOps, never()).entries(anyString());
    }

    @Test
    void resolveRawData_whenEventHasRawDataKey_shouldFetchAndParseFromRedis() throws Exception {
        // given
        String redisKey = "scan:raw:" + scanId + ":DNS";
        SurfaceResultEvent keyEvent = SurfaceResultEvent.successWithKey(scanId, surface, redisKey, 0);

        when(hashOps.entries(redisKey)).thenReturn(Map.of("data", "{\"result\":\"ok\"}"));
        // Use any(TypeReference.class) — Mockito cannot match the anonymous inline
        // TypeReference instance by reference equality, so eq() would never match.
        when(objectMapper.readValue(eq("{\"result\":\"ok\"}"), any(TypeReference.class)))
                .thenReturn(Map.of("result", "ok"));

        // when
        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                ReflectionTestUtils.invokeMethod(resultConsumer, "resolveRawData", keyEvent);

        // then
        assertThat(result).containsEntry("result", "ok");
    }

    @Test
    void resolveRawData_whenNeitherInlineNorKey_shouldReturnEmptyMap() {
        // given — failureEvent carries no rawData and no rawDataKey
        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                ReflectionTestUtils.invokeMethod(resultConsumer, "resolveRawData", failureEvent);

        // then
        assertThat(result).isEmpty();
    }

    // ==================== BACKOFF CALCULATION TESTS ====================

    @Test
    void calculateBackoffSeconds_shouldGrowExponentiallyWithJitter() {
        // Formula: 5 * 2^(attempt-1) with ±20% jitter
        // attempt=1 → base=5s  → window [4, 6]
        // attempt=2 → base=10s → window [8, 12]
        // attempt=3 → base=20s → window [16, 24]
        // Windows don't overlap, so the monotonic assertions are guaranteed to hold.
        long backoff1 = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateBackoffSeconds", 1);
        long backoff2 = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateBackoffSeconds", 2);
        long backoff3 = ReflectionTestUtils.invokeMethod(resultConsumer, "calculateBackoffSeconds", 3);

        assertThat(backoff1).isBetween(4L, 6L);
        assertThat(backoff2).isBetween(8L, 12L);
        assertThat(backoff3).isBetween(16L, 24L);
        assertThat(backoff2).isGreaterThan(backoff1);
        assertThat(backoff3).isGreaterThan(backoff2);
    }

    // ==================== WRITE TO RETRY ZSET TESTS ====================

    @Test
    void writeToRetryZset_shouldStoreAllMetadataAndEnqueueInZSet() throws Exception {
        // given
        // Use any() so the stub matches regardless of forRetry() object identity —
        // the production code calls originalEvent.forRetry(nextAttempt) and the
        // returned instance won't be reference-equal to anything captured in the test.
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"scanId\":\"" + scanId + "\"}");

        // when
        ReflectionTestUtils.invokeMethod(
                resultConsumer, "writeToRetryZset", failureEvent, 2, 10L);

        // then
        verify(hashOps).putAll(anyString(), anyMap());
        verify(redisTemplate).expire(anyString(), eq(Duration.ofHours(24)));
        verify(zSetOps).add(eq(RedisConfig.Keys.RETRY_ZSET), anyString(), anyDouble());
    }

    @Test
    void writeToRetryZset_whenSerializationFails_shouldSwallowExceptionAndNotWriteToRedis() throws Exception {
        // given
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("Serialization error"));

        // when — the production method catches and logs; must not propagate
        ReflectionTestUtils.invokeMethod(
                resultConsumer, "writeToRetryZset", failureEvent, 2, 10L);

        // then — nothing must have been written to Redis
        verify(hashOps, never()).putAll(anyString(), anyMap());
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    // ==================== CONSUMER GROUP TESTS ====================

    @Test
    void ensureConsumerGroup_whenGroupDoesNotExist_shouldCreateIt() {
        // given
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString())).thenReturn("OK");

        // when
        ReflectionTestUtils.invokeMethod(resultConsumer, "ensureConsumerGroup");

        // then
        verify(streamOps).createGroup(
                eq(RedisConfig.Keys.SURFACE_RESULT_STREAM),
                any(ReadOffset.class),
                eq(RedisConfig.CONSUMER_GROUP));
    }

    @Test
    void ensureConsumerGroup_whenGroupAlreadyExists_shouldSwallowExceptionSilently() {
        // given
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"));

        // when — must not propagate
        ReflectionTestUtils.invokeMethod(resultConsumer, "ensureConsumerGroup");

        // then — the attempt was still made (exception was swallowed, not skipped)
        verify(streamOps).createGroup(anyString(), any(ReadOffset.class), anyString());
    }
}