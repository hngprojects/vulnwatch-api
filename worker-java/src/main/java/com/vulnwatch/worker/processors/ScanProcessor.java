package com.vulnwatch.worker.processors;

import com.vulnwatch.worker.ai.AiEnricher;
import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.*;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ai.EnrichedScanResult;
import com.vulnwatch.worker.queue.RedisResultPublisher;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.repository.ScanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScanProcessor {

  private final List<Scanner> scanners;
  private final ScannerFilter scannerFilter;
  private final AiEnricher aiEnricher;
  private final ScanRepository scanRepository;
  private final FindingRepository findingRepository;
  private final RedisResultPublisher resultPublisher;
  private final ExecutorService executor;
  private final Clock clock;

  @Value("${scan.timeout:30}")
  private static final int SCANNER_TIMEOUT_SECONDS = 30;

  /** Main entry point - follows Single Responsibility + clear orchestration flow. */
  public void process(ScanJob job) {
    UUID scanId = job.getScanId();
    log.info("Processing scan job: scanId={}, targetTypes={}", scanId, job.getScanTypes());

    try {
      markScanRunning(scanId);

      List<Scanner> eligibleScanners =
          scannerFilter.filterByAnyTargetType(scanners, job.getScanTypes());
      log.info("Found {} eligible scanners for scan {}", eligibleScanners.size(), scanId);

      List<ScanResult> rawResults = executeScanners(eligibleScanners, job);

      AggregatedScanData aggregated = aggregateResults(scanId, job, rawResults);
      logFailures(aggregated);

      EnrichedScanResult enriched = aiEnricher.enrich(aggregated);

      persistResults(scanId, enriched);
      publishCompletion(scanId, enriched);

      log.info(
          "Scan completed successfully: scanId={}, score={}, findings={}",
          scanId,
          enriched.getSecurityScore(),
          enriched.getFindings().size());

    } catch (Exception e) {
      handleScanFailure(scanId, e);
    }
  }

  @Transactional
  protected void markScanRunning(UUID scanId) {
    scanRepository
        .findById(scanId)
        .ifPresentOrElse(
            scan -> {
              scan.markRunning();
              scanRepository.save(scan);
              log.info("Scan {} marked as RUNNING", scanId);
            },
            () -> {
              log.error("Could not find scan with ID {} to mark as RUNNING", scanId);
            });
  }

  private List<ScanResult> executeScanners(List<Scanner> scanners, ScanJob job) {
    List<Future<ScanResult>> futures = submitAllScanners(scanners, job);
    return collectResultsWithTimeout(scanners, futures);
  }

  private List<Future<ScanResult>> submitAllScanners(List<Scanner> eligibleScanners, ScanJob job) {
    return eligibleScanners.stream()
        .map(scanner -> executor.submit(() -> scanner.scan(job)))
        .toList();
  }

  private List<ScanResult> collectResultsWithTimeout(
      List<Scanner> scanners, List<Future<ScanResult>> futures) {
    return IntStream.range(0, futures.size())
        .mapToObj(
            i -> {
              Future<ScanResult> future = futures.get(i);
              Scanner scanner = scanners.get(i);
              SurfaceType surface = scanner.getSurfaceType();

              return getResultWithTimeout(future, scanner.getClass().getSimpleName(), surface);
            })
        .toList();
  }

  private ScanResult getResultWithTimeout(
      Future<ScanResult> future, String scannerName, SurfaceType surface) {
    try {
      return future.get(SCANNER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return ScanResult.timeout(null, scannerName, surface, SCANNER_TIMEOUT_SECONDS);
    } catch (Exception e) {
      return ScanResult.failure(null, scannerName, surface, e);
    }
  }

  private AggregatedScanData aggregateResults(UUID scanId, ScanJob job, List<ScanResult> results) {
    List<ScanResult> successes = results.stream().filter(ScanResult::isSuccess).toList();

    List<FailureInfo> failures =
        results.stream()
            .filter(r -> !r.isSuccess())
            .map(r -> new FailureInfo(r.getScannerName(), r.getSurface(), r.getErrorMessage()))
            .toList();

    return AggregatedScanData.builder()
        .scanId(scanId)
        .scanJob(job)
        .successfulResults(successes)
        .failures(failures)
        .build();
  }

  private void logFailures(AggregatedScanData aggregated) {
    if (!aggregated.getFailedSurfaces().isEmpty()) {
      log.warn(
          "Scan {} had failures on surfaces: {}",
          aggregated.getScanId(),
          aggregated.getFailedSurfaces());
    }
  }

  @Transactional
  protected void persistResults(UUID scanId, EnrichedScanResult enriched) {
    log.info(
        "Persisting results for scanId: {} with score: {}", scanId, enriched.getSecurityScore());

    scanRepository
        .findById(scanId)
        .ifPresentOrElse(
            scan -> {
              scan.markCompleted(enriched.getSecurityScore());
              scanRepository.save(scan);
            },
            () -> {
              log.error("Failed to persist results: Scan {} not found in database", scanId);
              return;
            });

    List<Finding> findings = enriched.getFindings();
    if (findings != null && !findings.isEmpty()) {

      findings.forEach(f -> f.setScanId(scanId));
      findingRepository.saveAll(findings);
      log.info("Saved {} findings for scanId: {}", findings.size(), scanId);
    }
  }

  private void publishCompletion(UUID scanId, EnrichedScanResult enriched) {
    resultPublisher.publishCompletion(
        scanId, ScanStatus.COMPLETED, enriched.getSecurityScore(), enriched.getFindings().size());
  }

  @Transactional
  protected void handleScanFailure(UUID scanId, Exception e) {
    log.error("Scan execution failed for scanId: {}. Error: {}", scanId, e.getMessage(), e);

    try {

      scanRepository
          .findById(scanId)
          .ifPresentOrElse(
              scan -> {
                scan.markFailed();
                scanRepository.save(scan);
                log.info("Scan {} marked as FAILED in database", scanId);
              },
              () -> {
                log.warn("Scan {} not found in database; skipping DB update.", scanId);
              });

      String errorMessage = (e.getMessage() != null) ? e.getMessage() : "Internal Worker Error";
      resultPublisher.publishFailure(scanId, errorMessage);

      log.info("Successfully published failure notification for scanId: {}", scanId);

    } catch (Exception secondaryException) {
      log.error(
          "CRITICAL: Failed to record scan failure for scanId: {}. Secondary error: {}",
          scanId,
          secondaryException.getMessage());
    }
  }

  private Instant now() {
    return Instant.now(clock);
  }
}
