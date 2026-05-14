package com.vulnwatch.worker.scanners.http;

import com.vulnwatch.worker.dto.FindingResponse;
import com.vulnwatch.worker.dto.ScanResponse;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.scanners.http.checks.AdminPanelCheck;
import com.vulnwatch.worker.scanners.http.checks.DirectoryListingCheck;
import com.vulnwatch.worker.scanners.http.checks.ExposureCheck;
import com.vulnwatch.worker.scanners.http.checks.HeaderCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HttpParallelScanner {

  private final HeaderCheck headerCheck;
  private final ExposureCheck exposureCheck;
  private final AdminPanelCheck adminPanelCheck;
  private final DirectoryListingCheck directoryListingCheck;
  private final Executor executor;

  public ScanResponse scan(ScanJob job) {
    CompletableFuture<List<FindingResponse>> headerTask =
        CompletableFuture.supplyAsync(() -> safe(headerCheck.scan(job.getDomain())), executor);

    CompletableFuture<List<FindingResponse>> exposureTask =
        CompletableFuture.supplyAsync(() -> safe(exposureCheck.scan(job.getDomain())), executor);

    CompletableFuture<List<FindingResponse>> adminTask =
        CompletableFuture.supplyAsync(() -> safe(adminPanelCheck.scan(job.getDomain())), executor);

    CompletableFuture<List<FindingResponse>> directoryTask =
        CompletableFuture.supplyAsync(
            () -> safe(directoryListingCheck.scan(job.getDomain())), executor);

    CompletableFuture.allOf(headerTask, exposureTask, adminTask, directoryTask).join();

    List<FindingResponse> findings = new ArrayList<>();
    findings.addAll(headerTask.join());
    findings.addAll(exposureTask.join());
    findings.addAll(adminTask.join());
    findings.addAll(directoryTask.join());

    return ScanResponse.builder().findings(findings).build();
  }

  private List<FindingResponse> safe(List<FindingResponse> findings) {
    try {
      return findings;
    } catch (Exception e) {
      return List.of(); // fail safe
    }
  }
}
