package com.vulnwatch.worker.scanners.dns.utility;

import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.vulnwatch.worker.config.GeoIpManager;
import com.vulnwatch.worker.scanners.dns.models.IpMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;

/** Utility class to look up ASN and Country Databases */
@Service
@RequiredArgsConstructor
public class AsnLookupService {

  private final GeoIpManager geoIpManager;

  public IpMetadata lookup(InetAddress addr) throws IOException {
    try {
      AsnResponse response = geoIpManager.asnReader().asn(addr);
      CountryResponse countryResponse = geoIpManager.countryReader().country(addr);

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
