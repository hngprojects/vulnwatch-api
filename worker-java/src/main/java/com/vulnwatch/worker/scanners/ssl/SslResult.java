package com.vulnwatch.worker.scanners.ssl;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SslResult {
  private String issuer;
  private String subject;
  private LocalDate validFrom;
  private LocalDate expiryDate;
  private int daysUntilExpiry;
  private List weakProtocols;
  private List findings;
}
