package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rule for extracting insights on DNSSEC posture */
@Component
public class DnssecRules implements Rule {
  @Override
  public List<Finding> evaluate(ScanContext context) {
    List<Finding> findings = new ArrayList<>();

    context
        .dnssecMap()
        .forEach(
            (k, v) -> {
              if (Objects.equals(v, "BOGUS")) {
                findings.add(Finding.medium("DNSSEC_ISSUE", k + " dnssec records might be bogus"));
              }
            });

    return findings;
  }
}
