package com.vulnwatch.worker.scanners.dns.utility;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.vulnwatch.worker.config.GeoIpManager;
import com.vulnwatch.worker.scanners.dns.models.IpMetadata;
import io.opencensus.stats.Aggregation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

/** Utility class to look up ASN and Country Databases */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsnLookupService {

  private final GeoIpManager geoIpManager;

  public IpMetadata lookup(InetAddress addr) throws IOException {
    try {
      Optional<DatabaseReader> asnReader = geoIpManager.asnReader();
      Optional<DatabaseReader> countryReader = geoIpManager.countryReader();

      if(asnReader.isEmpty()||countryReader.isEmpty()){
        log.warn("Either or both databases are unavailable");
        return new IpMetadata("", 0, "", "");
      }
      AsnResponse response = asnReader.get().asn(addr);
      CountryResponse countryResponse = countryReader.get().country(addr);

      return new IpMetadata(
          addr.toString(),
          response.autonomousSystemNumber().intValue(),
          response.autonomousSystemOrganization(),
          countryResponse.country().name());
    } catch (GeoIp2Exception e) {
      return new IpMetadata(addr.toString(), -1, "UNKNOWN", "UNKNOWN");
    }
  }
}
