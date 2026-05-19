package com.vulnwatch.worker.scanners.dns;

import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import org.springframework.stereotype.Service;

@Service
public class DnsScanner implements Scanner {

  /**
   * Performs a DNS scan for the given domain. Beginners: This is where we check for MX, TXT, and A
   * records.
   *
   * @return
   */
  public ScanResult scan(ScanJob job) {
    System.out.println("Starting DNS scan for domain: " + job.getDomain());
    // Implementation logic for DNS lookup...
    return null;
  }

  @Override
  public TargetType getTargetType() {
    return null;
  }

  @Override
  public SurfaceType getSurfaceType() {
    return null;
  }
}
