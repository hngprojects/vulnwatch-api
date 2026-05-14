package com.vulnwatch.worker.scanners.http.checks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vulnwatch.worker.dto.FindingResponse;
import com.vulnwatch.worker.util.HttpUtils;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class HeaderCheckTest {
  private HeaderCheck headerCheck;

  @BeforeEach
  void setUp() {
    headerCheck = new HeaderCheck();
  }

  @Test
  void shouldDetectMissingSecurityHeaders() {

    try (MockedStatic<HttpUtils> mock = Mockito.mockStatic(HttpUtils.class)) {

      HttpResponse<String> response = mock(HttpResponse.class);

      mock.when(() -> HttpUtils.sendGet("https://example.com")).thenReturn(response);

      when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));

      List<FindingResponse> result = headerCheck.scan("example.com");

      assertFalse(result.isEmpty());
    }
  }
}
