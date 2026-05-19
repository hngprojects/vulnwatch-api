package com.vulnwatch.worker.scanners.dns.utility;

import com.vulnwatch.worker.config.GeoIpManager;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** ASN and Country Database reload scheduler */
@Service
@RequiredArgsConstructor
public class MmdbReloadScheduler {

  private final GeoIpManager geoIpManager;

  /** Scheduled job to reload the databases by 3AM every Sunday */
  @Scheduled(cron = "0 0 3 * * SUN")
  public void reloadWeekly() {

    try {
      geoIpManager.reloadCountryDatabase();
      geoIpManager.reloadAsnDatabase();
      System.out.println("MMDB reloaded");

    } catch (Exception e) {
      System.err.println("Failed to reload MMDB");
      e.printStackTrace();
    }
  }
}
