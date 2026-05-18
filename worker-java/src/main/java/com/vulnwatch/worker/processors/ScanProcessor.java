package com.vulnwatch.worker.processors;

import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.queue.ScanCompletionPublisher;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.ScanRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Receives a {@link ScanJob}, initializes per-surface state, and fans out
 * one scanner task per eligible surface into the shared executor.
 *
 * <p>This class is deliberately thin:
 * <ul>
 *   <li>It does NOT wait for scanner results (fire-and-forget via executor).</li>
 *   <li>It does NOT call AI or write findings — that belongs to ResultConsumer.</li>
 *   <li>It does NOT own retry logic — that belongs to RetryScheduler.</li>
 * </ul>
 *
 * <p><b>Idempotency:</b> submitting the same scanId twice is safe.
 * {@code stateManager.initSurfaces()} is a no-op when state already exists,
 * and {@code markScanRunning()} guards against downgrading a terminal scan.
 *
 * <p><b>Graceful shutdown:</b> {@link #shutdown()} waits up to
 * {@code scan.shutdown-timeout-seconds} for in-flight tasks before forcing
 * termination. Wire this via {@code @PreDestroy}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanProcessor {

  private final List<Scanner> scanners;
  private final ScanRepository scanRepository;
  private final ExecutorService executor;
  private final SurfaceStateManager stateManager;
  private final SurfaceEventPublisher surfaceEventPublisher;
  private final ScanCompletionPublisher scanCompletionPublisher;

  @Value("${scan.timeout-seconds:30}")
  private int scannerTimeoutSeconds;

  @Value("${scan.shutdown-timeout-seconds:60}")
  private int shutdownTimeoutSeconds;

  /**
   * Main entry point - processes a scan job from Redis.
   */
  public void process(ScanJob job) {
    UUID scanId = job.getScanId();
    log.info("Processing scan: scanId={}, targetTypes={}", scanId, job.getScanTypes());

    try {
      List<Scanner> eligible = filterScannersByTargetType(job.getScanTypes());

      if (eligible.isEmpty()) {
        log.warn("No eligible scanners for scan {}", scanId);
        publishStartupFailure(scanId, "No eligible scanners for requested target types");
        return;
      }

      boolean started = markScanRunning(scanId);
      if (!started) {
        log.warn("Scan {} is already terminal, skipping re-submission", scanId);
        return;
      }

      List<SurfaceType> surfaces = eligible.stream()
              .map(Scanner::getSurfaceType)
              .toList();

      stateManager.initSurfaces(scanId, surfaces);

      for (Scanner scanner : eligible) {
        submitScannerTask(scanId, scanner, job);
      }

      log.info("Submitted {} scanner tasks for scan {}", eligible.size(), scanId);

    } catch (Exception e) {
      log.error("Failed to start scan {}", scanId, e);
      publishStartupFailure(scanId, e.getMessage());
    }
  }

  /**
   * Submits a single scanner as a timed, self-contained task.
   * Uses CompletableFuture with timeout instead of nested Future
   * to avoid consuming two threads per scanner.
   */
  private void submitScannerTask(UUID scanId, Scanner scanner, ScanJob job) {
    CompletableFuture.supplyAsync(() -> {
              SurfaceType surface = scanner.getSurfaceType();
              String scannerName = scanner.getClass().getSimpleName();
              log.debug("Starting {} for scan {}", scannerName, scanId);

              long startTime = System.currentTimeMillis();
              ScanResult result = scanner.scan(job);
              long duration = System.currentTimeMillis() - startTime;

              log.debug("{} completed in {}ms for scan {}", scannerName, duration, scanId);

              return SurfaceResultEvent.success(
                      scanId,
                      surface,
                      result.getRawData(),
                      0,
                      duration
              );
            }, executor)
            .orTimeout(scannerTimeoutSeconds, TimeUnit.SECONDS)
            .handle((event, throwable) -> {
              if (throwable != null) {
                return handleScannerFailure(scanId, scanner, throwable);
              }
              surfaceEventPublisher.publish(event);
              return null;
            });
  }

  /**
   * Handles scanner failure (timeout, exception, interruption).
   * Returns a failure event for further processing (optional).
   */
  private SurfaceResultEvent handleScannerFailure(UUID scanId, Scanner scanner, Throwable throwable) {
    SurfaceType surface = scanner.getSurfaceType();
    String scannerName = scanner.getClass().getSimpleName();

    if (throwable instanceof TimeoutException) {
      log.error("{} timed out after {}s for scan {}", scannerName, scannerTimeoutSeconds, scanId);
      SurfaceResultEvent event = SurfaceResultEvent.failure(
              scanId, surface,
              "Scanner timed out after " + scannerTimeoutSeconds + "s", 0
      );
      surfaceEventPublisher.publish(event);
      return event;
    }

    if (throwable instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      log.warn("{} interrupted for scan {}", scannerName, scanId);
      SurfaceResultEvent event = SurfaceResultEvent.failure(
              scanId, surface, "Scanner interrupted", 0
      );
      surfaceEventPublisher.publish(event);
      return event;
    }

    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
    log.error("{} failed for scan {}", scannerName, scanId, cause);
    SurfaceResultEvent event = SurfaceResultEvent.failure(
            scanId, surface, cause.getMessage(), 0
    );
    surfaceEventPublisher.publish(event);
    return event;
  }

  /**
   * Filters scanners that match the job's target types.
   */
  private List<Scanner> filterScannersByTargetType(List<TargetType> targetTypes) {
    return scanners.stream()
            .filter(scanner -> targetTypes.contains(scanner.getTargetType()))
            .toList();
  }

  /**
   * Transitions the scan to RUNNING in the database.
   *
   * @return true if transition happened; false if scan already terminal or not found
   */
  private boolean markScanRunning(UUID scanId) {
    return scanRepository.findById(scanId)
            .map(scan -> {
              if (scan.isTerminal()) {
                log.warn("Scan {} already terminal ({}), will not re-run", scanId, scan.getStatus());
                return false;
              }
              scan.markRunning();
              scanRepository.save(scan);
              log.info("Scan {} → RUNNING", scanId);
              return true;
            })
            .orElseGet(() -> {
              log.error("Scan {} not found in database — cannot mark as RUNNING", scanId);
              return false;
            });
  }

  /**
   * Publishes a startup failure (e.g., no eligible scanners, DB error).
   * Marks the scan as FAILED in DB and notifies via completion publisher.
   */
  private void publishStartupFailure(UUID scanId, String reason) {
    try {
      scanRepository.findById(scanId).ifPresent(scan -> {
        scan.markFailed();
        scanRepository.save(scan);
      });
      scanCompletionPublisher.publishScanFailed(scanId, reason);
    } catch (Exception e) {
      log.error("Could not publish startup failure for scan {}: {}", scanId, reason, e);
    }
  }


  /**
   * Waits up to shutdownTimeoutSeconds for in-flight scanner tasks to complete
   * before forcing termination. Prevents silent result loss during restarts.
   */
  @PreDestroy
  public void shutdown() {
    log.info("ScanProcessor shutting down — waiting {}s for in-flight tasks", shutdownTimeoutSeconds);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
        log.warn("Executor did not terminate cleanly — forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    log.info("ScanProcessor shutdown complete");
  }
}