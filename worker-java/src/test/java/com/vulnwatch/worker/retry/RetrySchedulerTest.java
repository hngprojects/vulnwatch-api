package com.vulnwatch.worker.retry;

import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.ScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Key design decisions in this test class:
 *
 * 1. Real List<Scanner> instead of @Mock List<Scanner>
 *    Mocking a List forces you to stub .stream() everywhere, is fragile,
 *    and obscures intent. A real list populated in setUp() is simpler.
 *
 * 2. submitRetryTask tests execute the Runnable captured from executor.submit()
 *    The original tests invoked the private method directly, bypassing the
 *    executor entirely — executor.submit() was never called. We now capture
 *    the Runnable via ArgumentCaptor and run it on the test thread, which
 *    correctly exercises the full task path AND lets us verify submit() was called.
 *
 * 3. processRetryJob_whenScannerNotFound uses a valid SurfaceType that has no
 *    registered scanner, rather than an invalid enum string. The original used
 *    "UNKNOWN_SURFACE" which throws IllegalArgumentException during enum parsing —
 *    it wasn't testing the scanner-not-found branch at all.
 *
 * 4. Event content is verified (not just that publish() was called)
 *    Tests assert on the SurfaceResultEvent fields: success/failure status,
 *    attempt number, rawDataKey propagation, and null handling for blank keys.
 */
@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private DefaultRedisScript<List<String>> popAndRetryScript;

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private SurfaceEventPublisher surfaceEventPublisher;

    @Mock
    private ExecutorService executor;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ZSetOperations<String, Object> zSetOps;

    // Real list — no need to stub .stream() in every test
    private List<Scanner> scanners;

    private Scanner dnsScanner;
    private Scanner sslScanner;

    private RetryScheduler retryScheduler;

    private UUID scanId;
    private Scan scan;

    @BeforeEach
    void setUp() {
        dnsScanner = mock(Scanner.class);
        sslScanner = mock(Scanner.class);
        when(dnsScanner.getSurfaceType()).thenReturn(SurfaceType.DNS);
        when(sslScanner.getSurfaceType()).thenReturn(SurfaceType.SSL);

        scanners = List.of(dnsScanner, sslScanner);

        retryScheduler = new RetryScheduler(
                redisTemplate, popAndRetryScript, scanRepository,
                surfaceEventPublisher, executor, scanners
        );
        // @PostConstruct doesn't fire in unit tests — call it manually
        ReflectionTestUtils.invokeMethod(retryScheduler, "buildScannerMap");

        scanId = UUID.randomUUID();
        scan = Scan.builder()
                .id(scanId)
                .userId(UUID.randomUUID())
                .status(ScanStatus.RUNNING)
                .targetType(TargetType.DOMAIN)
                .build();

    }

    // ==================== PROCESS RETRIES ====================

    @Test
    void processRetries_whenNoJobs_doesNothing() {
        when(redisTemplate.execute(eq(popAndRetryScript), anyList(), anyString(), anyString()))
                .thenReturn(List.of());

        retryScheduler.processRetries();

        verify(hashOps, never()).entries(anyString());
        verify(scanRepository, never()).findById(any());
        verify(executor, never()).submit(any(Runnable.class));
    }

    @Test
    void processRetries_whenJobsExist_submitsOneTaskPerKey() {
        stubHashOps();
        List<String> retryKeys = List.of("retry:job:abc-123:DNS", "retry:job:abc-123:SSL");
        when(redisTemplate.execute(eq(popAndRetryScript), anyList(), anyString(), anyString()))
                .thenReturn(retryKeys);
        when(hashOps.entries("retry:job:abc-123:DNS")).thenReturn(dnsMetadata());
        when(hashOps.entries("retry:job:abc-123:SSL")).thenReturn(sslMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        retryScheduler.processRetries();

        verify(executor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void processRetries_whenScriptThrows_doesNotPropagateException() {
        when(redisTemplate.execute(eq(popAndRetryScript), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // Should not throw — exception is caught and logged internally
        retryScheduler.processRetries();

        verify(executor, never()).submit(any(Runnable.class));
    }

    // ==================== PROCESS RETRY JOB ====================

    @Test
    void processRetryJob_whenMetadataEmpty_skipsWithoutDeletingHash() {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(Map.of());

        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);

        verify(scanRepository, never()).findById(any());
        // No hash to delete — the ZSET entry is gone but there's nothing to clean up
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void processRetryJob_whenNoScannerRegisteredForSurface_deletesHashAndDropsJob() {
        // SSL scanner exists but we'll use a retryKey that references a surface
        // with no registered scanner. Build a retryScheduler with only the DNS scanner
        // so SSL has no handler.
        RetryScheduler dnsOnlyScheduler = new RetryScheduler(
                redisTemplate, popAndRetryScript, scanRepository,
                surfaceEventPublisher, executor, List.of(dnsScanner)
        );
        ReflectionTestUtils.invokeMethod(dnsOnlyScheduler, "buildScannerMap");

        String retryKey = "retry:job:abc-123:SSL";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(sslMetadata());

        ReflectionTestUtils.invokeMethod(dnsOnlyScheduler, "processRetryJob", retryKey);

        verify(scanRepository, never()).findById(any());
        verify(redisTemplate).delete(retryKey);
    }

    @Test
    void processRetryJob_whenScanNotFound_deletesHashAndDropsJob() {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(dnsMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);

        verify(executor, never()).submit(any(Runnable.class));
        verify(redisTemplate).delete(retryKey);
    }

    @Test
    void processRetryJob_whenValidMetadata_submitsTaskToExecutor() {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(dnsMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);

        verify(executor).submit(any(Runnable.class));
    }



    /**
     * Captures the Runnable passed to executor.submit() and runs it on the test thread.
     * This is the correct way to test the task body — the original approach of invoking
     * the private method directly bypassed executor.submit() entirely.
     */
    @Test
    void submitRetryTask_whenScannerSucceeds_publishesSuccessEventAndDeletesHash() throws Exception {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(dnsMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        ScanResult result = ScanResult.success(scanId, "DnsScanner", SurfaceType.DNS, Map.of("ip", "1.2.3.4"));
        when(dnsScanner.scan(any(ScanJob.class))).thenReturn(result);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);
        verify(executor).submit(taskCaptor.capture());

        // Run the task on the test thread
        taskCaptor.getValue().run();

        ArgumentCaptor<SurfaceResultEvent> eventCaptor = ArgumentCaptor.forClass(SurfaceResultEvent.class);
        verify(surfaceEventPublisher).publish(eventCaptor.capture());

        SurfaceResultEvent published = eventCaptor.getValue();
        assertThat(published.isSuccess()).isTrue();
        assertThat(published.getScanId()).isEqualTo(scanId);
        assertThat(published.getSurface()).isEqualTo(SurfaceType.DNS);
        assertThat(published.getAttempt()).isEqualTo(2);           // from dnsMetadata()
        assertThat(published.getRawDataKey()).isEqualTo("scan:abc-123:raw:DNS");

        verify(redisTemplate).delete(retryKey);
    }

    @Test
    void submitRetryTask_whenScannerFails_publishesFailureEventAndDeletesHash() throws Exception {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(dnsMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(dnsScanner.scan(any(ScanJob.class))).thenThrow(new RuntimeException("timeout"));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);
        verify(executor).submit(taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<SurfaceResultEvent> eventCaptor = ArgumentCaptor.forClass(SurfaceResultEvent.class);
        verify(surfaceEventPublisher).publish(eventCaptor.capture());

        SurfaceResultEvent published = eventCaptor.getValue();
        assertThat(published.isSuccess()).isFalse();
        assertThat(published.getScanId()).isEqualTo(scanId);
        assertThat(published.getSurface()).isEqualTo(SurfaceType.DNS);
        assertThat(published.getAttempt()).isEqualTo(2);

        // Hash must be cleaned up even when scanner throws
        verify(redisTemplate).delete(retryKey);
    }

    @Test
    void submitRetryTask_whenRawDataKeyIsBlank_doesNotSetRawDataKeyOnEvent() throws Exception {
        // SSL metadata has rawDataKey = "" — should be treated as null
        String retryKey = "retry:job:abc-123:SSL";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(sslMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        ScanResult result = ScanResult.success(scanId, "SslScanner", SurfaceType.SSL, Map.of());
        when(sslScanner.scan(any(ScanJob.class))).thenReturn(result);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);
        verify(executor).submit(taskCaptor.capture());

        taskCaptor.getValue().run();

        ArgumentCaptor<SurfaceResultEvent> eventCaptor = ArgumentCaptor.forClass(SurfaceResultEvent.class);
        verify(surfaceEventPublisher).publish(eventCaptor.capture());

        // Blank rawDataKey must not be forwarded to the event
        assertThat(eventCaptor.getValue().getRawDataKey()).isNull();
    }

    @Test
    void submitRetryTask_hashIsDeletedEvenIfPublisherThrows() throws Exception {
        String retryKey = "retry:job:abc-123:DNS";
        stubHashOps();
        when(hashOps.entries(retryKey)).thenReturn(dnsMetadata());
        when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));

        ScanResult result = ScanResult.success(scanId, "DnsScanner", SurfaceType.DNS, Map.of());
        when(dnsScanner.scan(any(ScanJob.class))).thenReturn(result);
        doThrow(new RuntimeException("stream unavailable")).when(surfaceEventPublisher).publish(any());

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ReflectionTestUtils.invokeMethod(retryScheduler, "processRetryJob", retryKey);
        verify(executor).submit(taskCaptor.capture());

        // Task should not propagate exception out of the Runnable
        taskCaptor.getValue().run();

        // Hash cleanup must still happen
        verify(redisTemplate).delete(retryKey);
    }

    // ==================== BUILD SCANNER MAP ====================

    @Test
    void buildScannerMap_indexesScannersBySurfaceType() {
        // Re-invoke to confirm idempotency
        ReflectionTestUtils.invokeMethod(retryScheduler, "buildScannerMap");

        @SuppressWarnings("unchecked")
        Map<SurfaceType, Scanner> map =
                (Map<SurfaceType, Scanner>) ReflectionTestUtils.getField(retryScheduler, "scannerMap");

        assertThat(map).containsEntry(SurfaceType.DNS, dnsScanner)
                .containsEntry(SurfaceType.SSL, sslScanner);
    }

    @Test
    void buildScannerMap_keepFirstOnDuplicate() {
        Scanner duplicateDns = mock(Scanner.class);
        when(duplicateDns.getSurfaceType()).thenReturn(SurfaceType.DNS);

        RetryScheduler schedulerWithDuplicate = new RetryScheduler(
                redisTemplate, popAndRetryScript, scanRepository,
                surfaceEventPublisher, executor, List.of(dnsScanner, duplicateDns)
        );
        ReflectionTestUtils.invokeMethod(schedulerWithDuplicate, "buildScannerMap");

        @SuppressWarnings("unchecked")
        Map<SurfaceType, Scanner> map =
                (Map<SurfaceType, Scanner>) ReflectionTestUtils.getField(schedulerWithDuplicate, "scannerMap");

        assertThat(map.get(SurfaceType.DNS)).isSameAs(dnsScanner);
    }

    @Test
    void buildScannerMap_skipsScannersWithNullSurfaceType() {
        Scanner nullSurfaceScanner = mock(Scanner.class);
        when(nullSurfaceScanner.getSurfaceType()).thenReturn(null);

        RetryScheduler scheduler = new RetryScheduler(
                redisTemplate, popAndRetryScript, scanRepository,
                surfaceEventPublisher, executor, List.of(dnsScanner, nullSurfaceScanner)
        );
        ReflectionTestUtils.invokeMethod(scheduler, "buildScannerMap");

        @SuppressWarnings("unchecked")
        Map<SurfaceType, Scanner> map =
                (Map<SurfaceType, Scanner>) ReflectionTestUtils.getField(scheduler, "scannerMap");

        assertThat(map).hasSize(1).containsKey(SurfaceType.DNS);
    }

    // ==================== GET RETRY QUEUE SIZE ====================

    @Test
    void getRetryQueueSize_returnsValueFromRedis() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(RedisConfig.Keys.RETRY_ZSET)).thenReturn(5L);

        assertThat(retryScheduler.getRetryQueueSize()).isEqualTo(5L);
    }

    @Test
    void getRetryQueueSize_returnsZeroWhenRedisReturnsNull() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(RedisConfig.Keys.RETRY_ZSET)).thenReturn(null);

        assertThat(retryScheduler.getRetryQueueSize()).isZero();
    }

    // ==================== BUILD SCAN JOB ====================

    @Test
    void buildScanJob_mapsFieldsFromScanEntity() {
        ScanJob job = ReflectionTestUtils.invokeMethod(retryScheduler, "buildScanJob", scan);

        assertThat(job).isNotNull();
        assertThat(job.getScanId()).isEqualTo(scanId);
        assertThat(job.getRequestedBy()).isEqualTo(scan.getUserId());
        assertThat(job.getScanTypes()).containsExactly(TargetType.DOMAIN);
        assertThat(job.getEnqueuedAt()).isNotNull();
    }


    @Test
    void deleteHash_deletesRedisKey() {
        String retryKey = "retry:job:abc-123:DNS";
        ReflectionTestUtils.invokeMethod(retryScheduler, "deleteHash", retryKey);
        verify(redisTemplate).delete(retryKey);
    }

    @Test
    void deleteHash_doesNotThrowWhenRedisDeleteFails() {
        String retryKey = "retry:job:abc-123:DNS";
        doThrow(new RuntimeException("Redis error")).when(redisTemplate).delete(retryKey);

        // Should not propagate the exception
        ReflectionTestUtils.invokeMethod(retryScheduler, "deleteHash", retryKey);

        verify(redisTemplate).delete(retryKey);
    }


    /** Stub opsForHash only in tests that actually read metadata from Redis. */
    private void stubHashOps() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    private Map<Object, Object> dnsMetadata() {
        return Map.of(
                "scanId", scanId.toString(),
                "surface", "DNS",
                "attempt", "2",
                "rawDataKey", "scan:abc-123:raw:DNS"
        );
    }

    private Map<Object, Object> sslMetadata() {
        return Map.of(
                "scanId", scanId.toString(),
                "surface", "SSL",
                "attempt", "1",
                "rawDataKey", ""   // blank → should be treated as null
        );
    }
}