package com.vulnwatch.worker.scanners.dns.utility;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import com.vulnwatch.worker.scanners.dns.rules.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xbill.DNS.Record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Service that applies rules to scan context */
@Service
@RequiredArgsConstructor
public class RuleEngine {

  private final List<Rule> rules;

  public Map<String, Object> scanJob(ScanContext context) {

    Map<String, Object> result = new LinkedHashMap<>();
    List<Finding> findings = new ArrayList<>();

    result.put("aRecords", context.aRecordList()
            .stream()
            .map(record -> record.getAddress().toString())
            .toList());
    result.put("aaaaRecords", context.aaaaRecordList()
            .stream()
            .map(record -> record.getAddress().toString())
            .toList());
    result.put("nsRecords", context.nsRecordList().stream().map(record -> record.getTarget().toString()).toList());
    result.put("mxRecords", context.mxRecordList().stream().map(record-> record.getTarget().toString()).toList());
    result.put("dsRecords", context.dsRecordList().stream().map(Record::toString).toList());
    result.put("dnsKeyRecords", context.dnsKeyRecordList());
    result.put("cnameRecords", context.cnameRecordList());
    result.put("txtRecords", context.txtRecordList().stream().map(record->record.toString()).toList());
    result.put("ipMetadata", context.ipMetadataList());

    for (Rule rule : rules) {
      findings.addAll(rule.evaluate(context));
    }
    result.put("findings", findings);

    return result;
  }
}
