package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Rule for processing DMARC records */
@Component
public class DmarcRules implements Rule {
  @Override
  public List<Finding> evaluate(ScanContext context) {
    if (context.dmarcList().isEmpty()) {
      return List.of(Finding.high("DMARC_MISSING", "No DMARC record found"));
    } else if (context.txtRecordList().size() > 1) {
      return List.of(Finding.high("DMARC_MISCONFIGURED", "No DMARC record found"));
    } else {

      String txt = String.join("", context.dmarcList().getFirst().getStrings());
      List<Finding> findings = new ArrayList<>();

      if (txt.contains("p=none")) {
        findings.add(Finding.medium("DMARC_MONITORING_ONLY", "DMARC policy is set to p=none"));
      }

      if (!txt.contains("rua=")) {
        findings.add(Finding.low("DMARC_NO_REPORTING", "DMARC missing aggregate reports (rua)"));
      }

      if (txt.contains("pct=0") || txt.contains("pct=10")) {
        findings.add(
            Finding.medium("DMARC_WEAK_ENFORCEMENT", "DMARC enforcement is partial (low pct)"));
      }

      return findings;
    }
  }
}
