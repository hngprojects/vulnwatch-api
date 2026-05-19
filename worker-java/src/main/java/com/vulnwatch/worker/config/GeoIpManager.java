package com.vulnwatch.worker.config;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
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

  public Optional<DatabaseReader> asnReader() {
    DatabaseReader reader = readerRefAsn.get();
    if (reader==null){
      return Optional.empty();
    }
    return Optional.of(reader);
  }

  public Optional<DatabaseReader> countryReader() {
    DatabaseReader reader = readerRefCountry.get();
    if (reader==null){
      return Optional.empty();
    }
    return Optional.of(reader);
  }

  public void reloadAsnDatabase() throws IOException {

    File dbFile = new File(asnDbPath);
    if (!dbFile.exists() || !dbFile.isFile()) {
      log.warn("ASN DB not available at {}", countryDbPath);
      return;
    }

    DatabaseReader newReader = new DatabaseReader.Builder(dbFile).withCache(new CHMCache()).build();

    DatabaseReader oldReader = readerRefAsn.getAndSet(newReader);

    if (oldReader != null) {
      oldReader.close();
    }
  }

  public void reloadCountryDatabase() throws IOException {

    File dbFile =
        new File(countryDbPath);
    if (!dbFile.exists() || !dbFile.isFile()) {
      log.warn("Country DB not available at {}", countryDbPath);
      return;
    }

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
