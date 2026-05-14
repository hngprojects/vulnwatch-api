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
public class DirectoryListingCheck {

  public List<FindingResponse> scan(String domain) {
    List<FindingResponse> findings = new ArrayList<>();

    try {
      HttpResponse<String> response = HttpUtils.sendGet("https://" + domain);

      if (response.body().contains("Index of /")) {
        FindingResponse res = new FindingResponse();
        res.setSurface("EXPOSURE");
        res.setSeverity("HIGH");
        res.setTitle("Directory Listing Enabled");

        findings.add(res);
      }
    } catch (Exception e) {
      log.error("Exposure check failed for directory listing", e);
    }
    return findings;
  }
}
