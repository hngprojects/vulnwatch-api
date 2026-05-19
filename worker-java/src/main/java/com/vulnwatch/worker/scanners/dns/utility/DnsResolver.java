package com.vulnwatch.worker.scanners.dns.utility;

import com.vulnwatch.worker.scanners.dns.models.IpMetadata;
import com.vulnwatch.worker.scanners.dns.models.ScanContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import org.xbill.DNS.dnssec.ValidatingResolver;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Parallel DNS records lookups and aggregation */
@Service
@RequiredArgsConstructor
public class DnsResolver {

  private final AsnLookupService asnLookupService;
  private final RuleEngine ruleEngine;

  public CompletableFuture<ScanContext> resolveRecords(String domain) throws IOException {

    CompletableFuture<org.xbill.DNS.Record[]> a =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.A));

    CompletableFuture<org.xbill.DNS.Record[]> aaaa =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.AAAA));

    CompletableFuture<org.xbill.DNS.Record[]> ns =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.NS));

    CompletableFuture<org.xbill.DNS.Record[]> mx =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.MX));

    CompletableFuture<org.xbill.DNS.Record[]> ds =
        CompletableFuture.supplyAsync(() -> safeLookup(lastFourCharacters(domain), Type.DS));

    CompletableFuture<org.xbill.DNS.Record[]> dnsKey =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.DNSKEY));

    CompletableFuture<org.xbill.DNS.Record[]> txt =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.TXT));

    CompletableFuture<org.xbill.DNS.Record[]> cname =
        CompletableFuture.supplyAsync(() -> safeLookup("www." + domain, Type.CNAME));

    CompletableFuture<org.xbill.DNS.Record[]> soa =
        CompletableFuture.supplyAsync(() -> safeLookup(domain, Type.SOA));

    CompletableFuture<org.xbill.DNS.Record[]> dmarc =
        CompletableFuture.supplyAsync(() -> safeLookup("_dmarc." + domain, Type.TXT));

    return CompletableFuture.allOf(a, aaaa, mx, ds, dnsKey, txt)
        .thenApply(
            ignored -> {
              try {
                return aggregateResolver(
                    a.join(),
                    aaaa.join(),
                    ns.join(),
                    mx.join(),
                    ds.join(),
                    dnsKey.join(),
                    txt.join(),
                    cname.join(),
                    soa.join(),
                    dmarc.join());
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  private ScanContext aggregateResolver(
      Record[] a,
      Record[] aaaa,
      Record[] ns,
      Record[] mx,
      Record[] ds,
      Record[] dnsKey,
      Record[] txt,
      Record[] cname,
      Record[] soa,
      Record[] dmarc)
      throws IOException {

    List<ARecord> aRecordList = filterAndCast(a, ARecord.class);
    List<AAAARecord> aaaaRecordList = filterAndCast(aaaa, AAAARecord.class);
    List<NSRecord> nsRecordList = filterAndCast(ns, NSRecord.class);
    List<MXRecord> mxRecordList = filterAndCast(mx, MXRecord.class);
    List<DSRecord> dsRecordList = filterAndCast(ds, DSRecord.class);
    List<TXTRecord> txtRecordList = filterAndCast(txt, TXTRecord.class);
    List<DNSKEYRecord> dnsKeyRecordList = filterAndCast(dnsKey, DNSKEYRecord.class);
    List<RRSIGRecord> rrsigRecordList = filterAndCast(a, RRSIGRecord.class);
    List<CNAMERecord> cnameRecordList = filterAndCast(cname, CNAMERecord.class);
    List<SOARecord> soaRecordList = filterAndCast(soa, SOARecord.class);
    List<IpMetadata> ipMetadataList =
        extractIpMetadata(aRecordList, aaaaRecordList, mxRecordList, nsRecordList);
    List<TXTRecord> dmarcList = filterAndCast(dmarc, TXTRecord.class);

    Map<String, String> dnssecMap = new HashMap<>();
    dnssecMap.put("typeA", checkDnssec(a));
    dnssecMap.put("typeAAAA", checkDnssec(aaaa));
    dnssecMap.put("typeMX", checkDnssec(mx));
    dnssecMap.put("typeTXT", checkDnssec(txt));
    dnssecMap.put("typeCNAME", checkDnssec(cname));

    ScanContext context =
        ScanContext.builder()
            .aRecordList(aRecordList)
            .aaaaRecordList(aaaaRecordList)
            .nsRecordList(nsRecordList)
            .mxRecordList(mxRecordList)
            .dsRecordList(dsRecordList)
            .txtRecordList(txtRecordList)
            .dnsKeyRecordList(dnsKeyRecordList)
            .rrsigRecordList(rrsigRecordList)
            .cnameRecordList(cnameRecordList)
            .soaRecordList(soaRecordList)
            .dnssecMap(dnssecMap)
            .ipMetadataList(ipMetadataList)
            .dmarcList(dmarcList)
            .build();

    return context;
  }

  private org.xbill.DNS.Record[] safeLookup(String domain, int type) {
    try {
      Resolver base = new SimpleResolver("1.1.1.1");
      base.setEDNS(0, 0, Flags.DO);
      Resolver resolver = new ValidatingResolver(base);
      Lookup lookup = new Lookup(domain, type);
      lookup.setResolver(resolver);

      Record[] result = lookup.run();

      if (lookup.getResult() == Lookup.HOST_NOT_FOUND) {
        return new org.xbill.DNS.Record[0];
      }

      return result;

    } catch (Exception e) {
      return new org.xbill.DNS.Record[0]; // or null, or log + empty
    }
  }

  private static <T> List<T> filterAndCast(Record[] records, Class<T> type) {
    return records == null
        ? List.of()
        : Arrays.stream(records).filter(type::isInstance).map(type::cast).toList();
  }

  private String lastFourCharacters(String domain) {
    return domain.length() <= 3 ? domain : domain.substring(domain.length() - 3);
  }

  private String checkDnssec(Record[] records) {
    return (records == null || records.length == 0) ? "BOGUS" : "SECURE";
  }

  private List<IpMetadata> extractIpMetadata(
      List<ARecord> aRecords,
      List<AAAARecord> aaaaRecords,
      List<MXRecord> mxRecords,
      List<NSRecord> nsRecords)
      throws IOException {
    List<IpMetadata> ipMetadataList = new ArrayList<>();

    for (ARecord r : aRecords) {
      ipMetadataList.add(asnLookupService.lookup(r.getAddress()));
    }

    for (AAAARecord r : aaaaRecords) {
      ipMetadataList.add(asnLookupService.lookup(r.getAddress()));
    }

    return ipMetadataList;
  }
}
