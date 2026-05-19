package com.vulnwatch.worker.processors;

import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.queue.ScanCompletionPublisher;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.ScanRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanProcessorTest {


    @Mock private ScanRepository scanRepository;
    @Mock private SurfaceStateManager stateManager;
    @Mock private SurfaceEventPublisher surfaceEventPublisher;
    @Mock private ScanCompletionPublisher scanCompletionPublisher;
    @Mock private Scan scan;

    // Real scanner mocks — NOT a mocked List, so stream/filter work naturally
    private Scanner dnsScanner;
    private Scanner sslScanner;

    // Real single-threaded executor — tasks are deterministic, no race conditions
    private ExecutorService executor;
    private ScanProcessor processor;

    private UUID scanId;
    private ScanJob job;

    @BeforeEach
    void setUp() {
        dnsScanner = mock(Scanner.class);
        sslScanner = mock(Scanner.class);

        // Always stub identity methods — every test needs these
        lenient().when(dnsScanner.getTargetType()).thenReturn(TargetType.DOMAIN);
        lenient().when(dnsScanner.getSurfaceType()).thenReturn(SurfaceType.DNS);
        lenient().when(sslScanner.getTargetType()).thenReturn(TargetType.DOMAIN);
        lenient().when(sslScanner.getSurfaceType()).thenReturn(SurfaceType.SSL);

        executor = Executors.newFixedThreadPool(5);

        // Real List — stream/filter/isEmpty all work without stubbing
        processor = new ScanProcessor(
                List.of(dnsScanner, sslScanner),
                scanRepository,
                executor,
                stateManager,
                surfaceEventPublisher,
                scanCompletionPublisher
        );

        ReflectionTestUtils.setField(processor, "scannerTimeoutSeconds", 5);
        ReflectionTestUtils.setField(processor, "shutdownTimeoutSeconds", 10);

        scanId = UUID.randomUUID();
        job = ScanJob.builder()
                .scanId(scanId)
                .requestedBy(UUID.randomUUID())
                .domain("example.com")
                .scanTypes(List.of(TargetType.DOMAIN))
                .enqueuedAt(Instant.now())
                .build();

        // Default: scan exists and is not terminal
        lenient().when(scanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        lenient().when(scan.isTerminal()).thenReturn(false);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }


    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("marks scan RUNNING, initialises surfaces, publishes success events for all scanners")
        void process_allScannersSucceed_publishesSuccessEvents() {
            when(dnsScanner.scan(job)).thenReturn(scanResult(SurfaceType.DNS));
            when(sslScanner.scan(job)).thenReturn(scanResult(SurfaceType.SSL));

            processor.process(job);

            // DB transition
            verify(scan).markRunning();
            verify(scanRepository).save(scan);

            // State init with both surfaces
            verify(stateManager).initSurfaces(
                    eq(scanId), eq(List.of(SurfaceType.DNS, SurfaceType.SSL)));

            // Both success events published (async — use timeout)
            ArgumentCaptor<SurfaceResultEvent> captor =
                    ArgumentCaptor.forClass(SurfaceResultEvent.class);
            verify(surfaceEventPublisher, timeout(5_000).times(2)).publish(captor.capture());

            assertThat(captor.getAllValues()).allMatch(SurfaceResultEvent::isSuccess);
            assertThat(captor.getAllValues())
                    .extracting(SurfaceResultEvent::getSurface)
                    .containsExactlyInAnyOrder(SurfaceType.DNS, SurfaceType.SSL);
        }

        @Test
        @DisplayName("success event carries a non-negative durationMs")
        void process_successEvent_hasDuration() {
            when(dnsScanner.scan(job)).thenReturn(scanResult(SurfaceType.DNS));
            when(sslScanner.scan(job)).thenReturn(scanResult(SurfaceType.SSL));

            processor.process(job);

            ArgumentCaptor<SurfaceResultEvent> captor =
                    ArgumentCaptor.forClass(SurfaceResultEvent.class);
            verify(surfaceEventPublisher, timeout(5_000).atLeastOnce()).publish(captor.capture());

            captor.getAllValues().forEach(e ->
                    assertThat(e.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L));
        }
    }



    @Nested
    @DisplayName("No eligible scanners")
    class NoEligibleScanners {

        @Test
        @DisplayName("publishes startup failure when no scanners match target type")
        void process_noMatchingScanners_publishesFailure() {
            ScanJob noMatchJob = job.toBuilder()
                    .scanTypes(List.of(TargetType.REPOSITORY)) // both scanners are DOMAIN only
                    .build();

            processor.process(noMatchJob);

            verify(scanCompletionPublisher).publishScanFailed(eq(scanId), anyString());
            verify(scan, never()).markRunning();
            verify(stateManager, never()).initSurfaces(any(), any());
            verify(surfaceEventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("publishes startup failure for empty scanTypes list")
        void process_emptyScanTypes_publishesFailure() {
            ScanJob emptyJob = job.toBuilder().scanTypes(List.of()).build();

            processor.process(emptyJob);

            verify(scanCompletionPublisher).publishScanFailed(eq(scanId), anyString());
            verify(stateManager, never()).initSurfaces(any(), any());
        }
    }



    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("skips processing when scan is already terminal")
        void process_terminalScan_skipped() {
            when(scan.isTerminal()).thenReturn(true);

            processor.process(job);

            verify(stateManager, never()).initSurfaces(any(), any());
            verify(surfaceEventPublisher, never()).publish(any());
            verify(scanCompletionPublisher, never()).publishScanFailed(any(), any());
        }

        @Test
        @DisplayName("publishes startup failure when scan not found in DB")
        void process_scanNotFound_publishesFailure() {
            when(scanRepository.findById(scanId)).thenReturn(Optional.empty());

            processor.process(job);

            verify(stateManager, never()).initSurfaces(any(), any());
            verify(surfaceEventPublisher, never()).publish(any());
            verify(scanCompletionPublisher).publishScanFailed(eq(scanId), anyString());
        }
    }


    @Nested
    @DisplayName("Scanner failures")
    class ScannerFailures {

        @Test
        @DisplayName("publishes failure event when a scanner throws")
        void process_scannerThrows_publishesFailureEvent() {
            when(dnsScanner.scan(job)).thenThrow(new RuntimeException("DNS lookup failed"));
            when(sslScanner.scan(job)).thenReturn(scanResult(SurfaceType.SSL));

            processor.process(job);

            ArgumentCaptor<SurfaceResultEvent> captor =
                    ArgumentCaptor.forClass(SurfaceResultEvent.class);
            verify(surfaceEventPublisher, timeout(5_000).times(2)).publish(captor.capture());

            SurfaceResultEvent dnsEvent = captor.getAllValues().stream()
                    .filter(e -> e.getSurface() == SurfaceType.DNS)
                    .findFirst().orElseThrow();

            assertThat(dnsEvent.isSuccess()).isFalse();
            assertThat(dnsEvent.getErrorMessage()).contains("DNS lookup failed");
        }

        @Test
        @DisplayName("one scanner failing does not prevent the other from publishing")
        void process_oneFailsOneSucceeds_bothEventsPublished() {
            when(dnsScanner.scan(job)).thenThrow(new RuntimeException("timeout"));
            when(sslScanner.scan(job)).thenReturn(scanResult(SurfaceType.SSL));

            processor.process(job);

            ArgumentCaptor<SurfaceResultEvent> captor =
                    ArgumentCaptor.forClass(SurfaceResultEvent.class);
            verify(surfaceEventPublisher, timeout(5_000).times(2)).publish(captor.capture());

            assertThat(captor.getAllValues())
                    .anySatisfy(e -> assertThat(e.isSuccess()).isTrue())
                    .anySatisfy(e -> assertThat(e.isSuccess()).isFalse());
        }

        @Test
        @DisplayName("stateManager throwing causes startup failure, no scanners submitted")
        void process_stateManagerThrows_publishesStartupFailure() {
            doThrow(new RuntimeException("Redis unavailable"))
                    .when(stateManager).initSurfaces(any(), any());

            processor.process(job);

            verify(scanCompletionPublisher).publishScanFailed(eq(scanId), anyString());
            verify(surfaceEventPublisher, never()).publish(any());
        }
    }


    @Nested
    @DisplayName("Scanner filtering")
    class ScannerFiltering {

        @Test
        @DisplayName("only runs scanners whose targetType matches the job")
        void process_mixedScanners_onlyRunsMatchingOnes() {
            Scanner repoScanner = mock(Scanner.class);
            lenient().when(repoScanner.getTargetType()).thenReturn(TargetType.REPOSITORY);
            lenient().when(repoScanner.getSurfaceType()).thenReturn(SurfaceType.DEPENDENCY);

            ScanProcessor mixedProcessor = new ScanProcessor(
                    List.of(dnsScanner, repoScanner),
                    scanRepository, executor, stateManager,
                    surfaceEventPublisher, scanCompletionPublisher);
            ReflectionTestUtils.setField(mixedProcessor, "scannerTimeoutSeconds", 5);
            ReflectionTestUtils.setField(mixedProcessor, "shutdownTimeoutSeconds", 10);

            when(dnsScanner.scan(job)).thenReturn(scanResult(SurfaceType.DNS));

            mixedProcessor.process(job);

            verify(repoScanner, never()).scan(any());
            verify(surfaceEventPublisher, timeout(5_000).times(1)).publish(any());
        }
    }



    @Nested
    @DisplayName("Database operations")
    class DatabaseOperations {

        @Test
        @DisplayName("scan is saved with RUNNING status after markScanRunning")
        void process_validScan_markedRunning() {
            when(dnsScanner.scan(job)).thenReturn(scanResult(SurfaceType.DNS));
            when(sslScanner.scan(job)).thenReturn(scanResult(SurfaceType.SSL));

            processor.process(job);

            verify(scan).markRunning();
            verify(scanRepository).save(scan);
        }

        @Test
        @DisplayName("startup failure marks scan as FAILED in DB")
        void process_noEligibleScanners_marksFailedInDb() {
            ScanJob ipJob = job.toBuilder().scanTypes(List.of(TargetType.REPOSITORY)).build();

            processor.process(ipJob);

            verify(scan).markFailed();
            verify(scanRepository).save(scan);
        }
    }



    @Nested
    @DisplayName("Graceful shutdown")
    class GracefulShutdown {

        @Test
        @DisplayName("shutdown() shuts down the executor")
        void shutdown_executorTerminates() {
            processor.shutdown();
            assertThat(executor.isShutdown()).isTrue();
        }

        @Test
        @DisplayName("shutdown() is safe to call when no tasks are running")
        void shutdown_idleExecutor_doesNotThrow() {
            processor.shutdown(); // should complete immediately
        }
    }


    private ScanResult scanResult(SurfaceType surface) {
        return ScanResult.builder()
                .scanId(scanId)
                .surface(surface)
                .success(true)
                .rawData(Map.of("key", "value"))
                .build();
    }
}