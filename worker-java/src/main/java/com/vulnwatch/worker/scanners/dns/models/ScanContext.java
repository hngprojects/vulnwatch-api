package com.vulnwatch.worker.scanners.dns.models;

import lombok.Builder;
import org.xbill.DNS.*;

import java.util.List;
import java.util.Map;

/**
 * Scan context object, contains inputs to be processed
 *
 * @param aRecordList
 * @param aaaaRecordList
 * @param nsRecordList
 * @param mxRecordList
 * @param dsRecordList
 * @param txtRecordList
 * @param dnsKeyRecordList
 * @param rrsigRecordList
 * @param cnameRecordList
 * @param soaRecordList
 * @param ipMetadataList
 * @param dmarcList
 * @param dnssecMap
 */
@Builder
public record ScanContext(
    List<ARecord> aRecordList,
    List<AAAARecord> aaaaRecordList,
    List<NSRecord> nsRecordList,
    List<MXRecord> mxRecordList,
    List<DSRecord> dsRecordList,
    List<TXTRecord> txtRecordList,
    List<DNSKEYRecord> dnsKeyRecordList,
    List<RRSIGRecord> rrsigRecordList,
    List<CNAMERecord> cnameRecordList,
    List<SOARecord> soaRecordList,
    List<IpMetadata> ipMetadataList,
    List<TXTRecord> dmarcList,
    Map<String, String> dnssecMap) {

  public boolean dnssecConfigured() {
    return hasDS() || hasDNSKEY();
  }

  public boolean dnssecSigned() {
    return hasRRSIG() && hasDNSKEY();
  }

  public boolean dnssecLikelyEnabled() {
    return hasDS() && hasDNSKEY() && hasRRSIG();
  }

  public boolean hasDS() {
    return !dsRecordList.isEmpty();
  }

  public boolean hasDNSKEY() {
    return !dnsKeyRecordList.isEmpty();
  }

  public boolean hasRRSIG() {
    return !rrsigRecordList.isEmpty();
  }
}
