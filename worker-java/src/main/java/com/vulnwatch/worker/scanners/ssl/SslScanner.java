package com.vulnwatch.worker.scanners.ssl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.ScanJob;
import com.vulnwatch.worker.models.ScanResult;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Service;

@Service
public class SslScanner {
  // https default port
  private static final int PORT = 443;
  // Time control for invalid domine
  private static final int TIMEOUT_MS = 5000;
  // protocols decleared unsafe
  private static final List<String> WEAK_PROTOCOLS =
      Arrays.asList("SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1");

  public ScanResult scan(ScanJob job) {

    ObjectMapper mapper = new ObjectMapper();

    // initialize Ssl result
    SslResult result = new SslResult();

    String domain = job.getDomain();

    // initialize scan result
    ScanResult scanResult = new ScanResult();

    try {

      // socket and connect to port 443
      SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      SSLSocket socket = (SSLSocket) factory.createSocket();
      socket.connect(new InetSocketAddress(domain, PORT), TIMEOUT_MS);
      socket.startHandshake();

      // get Certificate
      Certificate[] certs = socket.getSession().getPeerCertificates();

      if (certs.length > 0 && certs[0] instanceof X509Certificate cert) {

        // set certificate details
        result.setIssuer(cert.getIssuerX500Principal().getName());
        result.setSubject(cert.getSubjectX500Principal().getName());
        result.setValidFrom(toLocalDate(cert.getNotBefore()));
        result.setExpiryDate(toLocalDate(cert.getNotAfter()));

        // Calculate days until expiry
        long daysUntilExpiry = LocalDate.now().until(result.getExpiryDate()).getDays();
        result.setDaysUntilExpiry((int) daysUntilExpiry);

        // check for expiry findings
        List<String> findings = new ArrayList<>();
        if (daysUntilExpiry < 0) {
          findings.add("CRITICAL: SSL certificate has EXPIRED");
        } else if (daysUntilExpiry <= 7) {
          findings.add("CRITICAL: SSL certificate expires in " + daysUntilExpiry + " days");
        } else if (daysUntilExpiry <= 30) {
          findings.add("HIGH: SSL certificate expires in " + daysUntilExpiry + " days");
        }

        // Check for weak protocols
        List<String> weakFound = new ArrayList<>();
        String[] enabledProtocols = socket.getEnabledProtocols();
        for (String protocol : enabledProtocols) {
          if (WEAK_PROTOCOLS.contains(protocol)) {
            weakFound.add(protocol);
          }
        }
        result.setWeakProtocols(weakFound);
        if (!weakFound.isEmpty()) {
          findings.add("HIGH: Weak protocols enabled: " + String.join(", ", weakFound));
        }

        result.setFindings(findings);
      }

      socket.close();

    } catch (java.net.ConnectException e) {
      // Could not reach port 443
      scanResult.setSuccess(false);
      scanResult.setErrorMessage("HIGH: Could not connect to port 443 — SSL may not be configured");

    } catch (Exception e) {
      scanResult.setSuccess(false);
      scanResult.setErrorMessage("HIGH: Could not connect to port 443 — SSL may not be configured");
    }

    Map<String, Object> rawData =
        mapper.convertValue(result, new TypeReference<Map<String, Object>>() {});

    // Time stamp
    Instant timestamp = Instant.now();

    // set scanner result
    scanResult.setScanId(job.getScanId());
    scanResult.setScannerName("SSL Scanner");
    scanResult.setSuccess(true);
    scanResult.setTimestamp(timestamp);
    scanResult.setSurface(SurfaceType.SSL);
    scanResult.setRawData(rawData);

    return scanResult;
  }

  // convert to modern LocalDate
  private LocalDate toLocalDate(Date date) {
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }
}
