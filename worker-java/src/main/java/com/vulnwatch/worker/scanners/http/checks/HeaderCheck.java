package com.vulnwatch.worker.scanners.http.checks;

import com.vulnwatch.worker.dto.FindingResponse;
import com.vulnwatch.worker.util.HttpUtils;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HeaderCheck {

  public List<FindingResponse> scan(String domain) {
    List<FindingResponse> findings = new ArrayList<>();

    try {
      HttpResponse<String> response = HttpUtils.sendGet("https://" + domain);

      checkHeader(response, findings, "Strict-Transport-Security", "Missing HSTS Header", "HIGH");
      checkHeader(
          response, findings, "Content-Security-Policy", "Missing Content Security Policy", "HIGH");
      checkHeader(
          response, findings, "X-Frame-Options", "Missing X-Frame-Options Header", "MEDIUM");
      checkHeader(
          response,
          findings,
          "X-Content-Type-Options",
          "Missing X-Content-Type-Options Header",
          "LOW");

    } catch (Exception e) {
      log.error("Header check failed for {}", domain, e);
    }

    return findings;
  }

  private void checkHeader(
      HttpResponse<String> response,
      List<FindingResponse> findings,
      String headerName,
      String title,
      String severity) {
    if (response.headers().firstValue(headerName).isEmpty()) {
      FindingResponse res = new FindingResponse();
      res.setSurface("HTTP_HEADERS");
      res.setSeverity(severity);
      res.setTitle(title);

      findings.add(res);
    }
  }
}
