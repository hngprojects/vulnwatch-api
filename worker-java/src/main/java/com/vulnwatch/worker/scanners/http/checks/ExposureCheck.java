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
public class ExposureCheck {
  private static final List<String> PATHS = List.of("/.env", "/.git", "/backup.zip", "/config.php");

  public List<FindingResponse> scan(String domain) {
    List<FindingResponse> findings = new ArrayList<>();

    for (String path : PATHS) {
      try {
        HttpResponse<String> response = HttpUtils.sendGet("https://" + domain + path);

        if (response.statusCode() == 200) {
          FindingResponse res = new FindingResponse();
          res.setSurface("EXPOSURE");
          res.setSeverity("CRITICAL");
          res.setTitle("Sensitive File Exposed");

          findings.add(res);
        }
      } catch (Exception e) {
        log.error("Exposure check failed for path {}", path, e);
      }
    }
    return findings;
  }
}
