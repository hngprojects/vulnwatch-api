package com.vulnwatch.worker.scanners.http;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnwatch.worker.dto.FindingResponse;
import com.vulnwatch.worker.dto.ScanResponse;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.scanners.http.checks.AdminPanelCheck;
import com.vulnwatch.worker.scanners.http.checks.DirectoryListingCheck;
import com.vulnwatch.worker.scanners.http.checks.ExposureCheck;
import com.vulnwatch.worker.scanners.http.checks.HeaderCheck;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@RequiredArgsConstructor
public class HttpParallelScannerTest {
  @Mock private HeaderCheck headerCheck;
  @Mock private ExposureCheck exposureCheck;
  @Mock private AdminPanelCheck adminPanelCheck;
  @Mock private DirectoryListingCheck directoryListingCheck;

  private Executor executor;

  private HttpParallelScanner scanner;

  @BeforeEach
  void setUp() {
    executor = Executors.newFixedThreadPool(4);

    scanner =
        new HttpParallelScanner(
            headerCheck, exposureCheck, adminPanelCheck, directoryListingCheck, executor);
  }

  @Test
  void shouldContinueEvenIfOneScannerFails() {

    ScanJob job = new ScanJob();
    job.setDomain("example.com");

    when(headerCheck.scan("example.com")).thenReturn(List.of());

    when(exposureCheck.scan("example.com"))
        .thenReturn(List.of(make("EXPOSURE", "CRITICAL", "Sensitive file")));

    when(adminPanelCheck.scan("example.com")).thenReturn(List.of(make("EXPOSURE", "LOW", "Admin")));

    when(directoryListingCheck.scan("example.com"))
        .thenReturn(List.of(make("EXPOSURE", "HIGH", "Directory listing")));

    ScanResponse response = scanner.scan(job);

    verify(headerCheck).scan("example.com");
    verify(exposureCheck).scan("example.com");
    verify(adminPanelCheck).scan("example.com");
    verify(directoryListingCheck).scan("example.com");
  }

  private FindingResponse make(String surface, String severity, String title) {
    FindingResponse f = new FindingResponse();
    f.setSurface(surface);
    f.setSeverity(severity);
    f.setTitle(title);
    return f;
  }
}
