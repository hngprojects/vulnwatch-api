package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import java.util.List;

/** General Rule interface for scan context processing */
public interface Rule {
  List<Finding> evaluate(ScanContext context);
}
