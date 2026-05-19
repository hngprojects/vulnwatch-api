package com.vulnwatch.worker.scanners.dns;

import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.interfaces.Scanner;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import com.vulnwatch.worker.scanners.dns.utility.DnsResolver;
import com.vulnwatch.worker.scanners.dns.utility.RuleEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DnsScanner implements Scanner {

  /**
   * Performs a DNS scan for the given domain. Beginners: This is where we check for MX, TXT, and A
   * records.
   */
  private final DnsResolver dnsResolver;

  private final RuleEngine ruleEngine;
  private static final String STRICT_DOMAIN_REGEX =
          "^(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$";

  @Override
  @Cacheable()
  public ScanResult scan(ScanJob job) {
    try {
      String domainName = validateDomain(job.getDomain());
      ScanContext scanContext = dnsResolver.resolveRecords(domainName).join();
      Map<String, Object> scanResult = ruleEngine.scanJob(scanContext);
      return ScanResult.success(job.getScanId(), "DNS_SCANNER", SurfaceType.DNS, scanResult);
    } catch (Exception e) {
      return ScanResult.failure(job.getScanId(), "DNS_SCANNER", SurfaceType.DNS, e);
    }
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.DOMAIN;
  }

  @Override
  public SurfaceType getSurfaceType() {
    return SurfaceType.DNS;
  }

  private String validateDomain(String domain){
    if (domain==null || domain.isBlank()){
      throw new IllegalArgumentException("Domain cannot be empty or null");
    }

    if (domain.matches(STRICT_DOMAIN_REGEX)){
      return domain;
    }

    else {
      throw new IllegalArgumentException("Domain does not follow input rules");
    }
  }
}
