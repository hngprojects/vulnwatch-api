package com.vulnwatch.worker.config;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class GeoIpManager {

  private final AtomicReference<DatabaseReader> readerRefAsn = new AtomicReference<>();
  private final AtomicReference<DatabaseReader> readerRefCountry = new AtomicReference<>();

  @Value("${geoip.asn.db-path}")
  private String asnDbPath;

  @Value("${geoip.country.db-path}")
  private String countryDbPath;

  @PostConstruct
  public void init() throws IOException {
    reloadAsnDatabase();
    reloadCountryDatabase();
  }

  public DatabaseReader asnReader() {
    return readerRefAsn.get();
  }

  public DatabaseReader countryReader() {
    return readerRefCountry.get();
  }

  public void reloadAsnDatabase() throws IOException {

    File dbFile = new File("/Users/mitchelntuen/Downloads/GeoLite2-ASN_20260511/GeoLite2-ASN.mmdb");

    DatabaseReader newReader = new DatabaseReader.Builder(dbFile).withCache(new CHMCache()).build();

    DatabaseReader oldReader = readerRefAsn.getAndSet(newReader);

    if (oldReader != null) {
      oldReader.close();
    }
  }

  public void reloadCountryDatabase() throws IOException {

    File dbFile =
        new File("/Users/mitchelntuen/Downloads/GeoLite2-Country_20260508/GeoLite2-Country.mmdb");

    DatabaseReader newReader = new DatabaseReader.Builder(dbFile).withCache(new CHMCache()).build();

    DatabaseReader oldReader = readerRefCountry.getAndSet(newReader);

    if (oldReader != null) {
      oldReader.close();
    }
  }

  @PreDestroy
  public void shutdown() {
    DatabaseReader readerAsn = readerRefAsn.getAndSet(null);
    if (readerAsn != null) {
      try {
        readerAsn.close();
      } catch (IOException ignored) {
      }
    }

    DatabaseReader readerCountry = readerRefCountry.getAndSet(null);
    if (readerCountry != null) {
      try {
        readerCountry.close();
      } catch (IOException ignored) {
      }
    }
  }
}
