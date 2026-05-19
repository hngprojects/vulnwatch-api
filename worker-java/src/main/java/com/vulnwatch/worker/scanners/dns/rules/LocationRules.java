package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.IpMetadata;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Rules for extracting insights from Country databases */
@Component
public class LocationRules implements Rule {

  @Override
  public List<Finding> evaluate(ScanContext context) {
    List<Finding> findings = new ArrayList<>();

    context
        .ipMetadataList()
        .forEach(
            ipMetadata -> {
              if (Objects.equals(ipMetadata.country(), "UNKNOWN")) {
                findings.add(
                    Finding.medium("UNRECOGNIZED_COUNTRY", "IP location country is unknown"));
              }
            });

    Set<String> countries =
        context.ipMetadataList().stream().map(IpMetadata::country).collect(Collectors.toSet());

    if (countries.size() > 1) {
      findings.add(
          Finding.info(
              "MULTIPLE_LOCATION_ASSOCIATION",
              "IP addresses resolve to multiple country locations: " + countries));
    }

    if (countries.size() == 1) {
      findings.add(
          Finding.info(
              "SINGLE_LOCATION_ASSOCIATION",
              "IP addresses resolve to a single country location: " + countries));
    }

    return findings;
  }
}
