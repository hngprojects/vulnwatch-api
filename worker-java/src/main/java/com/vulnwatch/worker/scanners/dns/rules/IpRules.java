package com.vulnwatch.worker.scanners.dns.rules;

import com.vulnwatch.worker.scanners.dns.models.Finding;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Rules for extracting insights on */
@Component
public class IpRules implements Rule {

  @Override
  public List<Finding> evaluate(ScanContext context) {

    List<Finding> findings = new ArrayList<>();

    if (context.aRecordList().isEmpty()) {
      findings.add(Finding.medium("MISSING_IPV4", "DNS lookup returned no IPv4 addresses"));
    }

    if (context.aaaaRecordList().isEmpty()) {
      findings.add(Finding.medium("MISSING_IPV6", "DNS lookup returned no IPv6 addresses"));
    }

    context
        .aRecordList()
        .forEach(
            aRecord -> {
              String ip = aRecord.getAddress().getHostAddress();

              if (isInternal(ip)) {
                findings.add(
                    Finding.high(
                        "PRIVATE_IPV4_LEAK", "A record resolves to internal IPv4 address: " + ip));
              }
            });

    context
        .aaaaRecordList()
        .forEach(
            aRecord -> {
              String ip = aRecord.getAddress().getHostAddress();

              if (isInternal(ip)) {
                findings.add(
                    Finding.high(
                        "PRIVATE_IPV6_LEAK", "A record resolves to internal IPv6 address: " + ip));
              }
            });

    return findings;
  }

  private boolean isInternal(String ip) {
    try {
      InetAddress address = InetAddress.getByName(ip);

      return address.isAnyLocalAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || isPrivateIPv6(address);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("IP address is not valid: " + ip);
    }
  }

  private boolean isPrivateIPv6(InetAddress addr) {

    String ip = addr.getHostAddress().toLowerCase();

    return ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd");
  }
}
