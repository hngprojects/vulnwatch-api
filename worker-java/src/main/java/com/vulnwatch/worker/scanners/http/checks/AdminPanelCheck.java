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
public class AdminPanelCheck {
  private static final List<String> ADMIN_PATHS =
      List.of("/admin", "/dashboard", "/wp-admin", "/login");

  public List<FindingResponse> scan(String domain) {
    List<FindingResponse> findings = new ArrayList<>();

    for (String path : ADMIN_PATHS) {
      try {
        HttpResponse response = HttpUtils.sendGet(domain);

        if (response.statusCode() == 200) {
          FindingResponse res = new FindingResponse();
          res.setSurface("EXPOSURE");
          res.setSeverity("LOW");
          res.setTitle("Public Admin Endpoint Detected");

          findings.add(res);
        }

      } catch (Exception e) {
        log.error("Exposure check failed for path {}", path, e);
      }
    }
    return findings;
  }
}
