package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import org.springframework.stereotype.Component;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.MXRecord;

import java.util.*;
import java.util.stream.Collectors;

/** Rule for extracting insights from TXT Records */
@Component
public class ttlRules implements Rule {
  @Override
  public List<Finding> evaluate(ScanContext context) {
    List<Finding> findings = new ArrayList<>();

    Map<String, Set<Long>> ttlMap = extractTTL(context);

    ttlMap.forEach(
        (k, v) -> {
          long min = v.stream().mapToLong(Long::longValue).min().orElse(-1);

          long max = v.stream().mapToLong(Long::longValue).max().orElse(-1);

          if (max - min > 70000) {
            findings.add(
                Finding.medium("TTL_CONFIGURATION", "Possible ttl misconfiguration in " + k));
          } else if (max - min > 3600 && max - min < 69000) {
            findings.add(Finding.low("TTL_CONFIGURATION", "Possible ttl misconfiguration in " + k));
          } else {
            findings.add(Finding.info("TTL_CONFIGURATION", "TTL is well configured" + k));
          }

          v.forEach(
              ttl -> {
                if (ttl > 100000) {
                  findings.add(
                      Finding.high("TTL_CONFIGURATION", "Possible ttl misconfiguration in  " + k));
                }
              });
        });

    return findings;
  }

  private Map<String, Set<Long>> extractTTL(ScanContext context) {
    HashMap<String, Set<Long>> ttlMap = new HashMap<>();

    ttlMap.put(
        "aRecord", context.aRecordList().stream().map(ARecord::getTTL).collect(Collectors.toSet()));
    ttlMap.put(
        "aaaaRecord",
        context.aaaaRecordList().stream().map(AAAARecord::getTTL).collect(Collectors.toSet()));
    ttlMap.put(
        "mxRecord",
        context.mxRecordList().stream().map(MXRecord::getTTL).collect(Collectors.toSet()));

    return ttlMap;
  }
}
