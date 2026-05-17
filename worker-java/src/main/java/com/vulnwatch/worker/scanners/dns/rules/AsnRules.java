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

/** Rule for processing with ASN lookups */
@Component
public class AsnRules implements Rule {

  @Override
  public List<Finding> evaluate(ScanContext context) {
    List<Finding> findings = new ArrayList<>();

    context
        .ipMetadataList()
        .forEach(
            ipMetadata -> {
              if (Objects.equals(ipMetadata.org(), "UNKNOWN")) {
                findings.add(
                    Finding.medium(
                        "UNRECOGNIZED_CDN",
                        "IP address: "
                            + ipMetadata.ip()
                            + " is not associated with any known CDN service"));
              }
            });

    Set<String> orgs =
        context.ipMetadataList().stream().map(IpMetadata::org).collect(Collectors.toSet());

    if (orgs.size() > 1) {
      findings.add(
          Finding.medium(
              "MULTIPLE_ORG_ASSOCIATION",
              "IP addresses resolve to multiple organizations: " + orgs));
    }

    return findings;
  }
}
