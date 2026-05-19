package com.vulnwatch.worker.scanners.dns.models;

/**
 * Data object that represents security insights from scan
 *
 * @param severity
 * @param title
 * @param description
 */
public record Finding(Severity severity, String title, String description) {
  public static Finding info(String title, String description) {
    return new Finding(Severity.INFO, title, description);
  }

  public static Finding low(String title, String description) {
    return new Finding(Severity.LOW, title, description);
  }

  public static Finding medium(String title, String description) {
    return new Finding(Severity.MEDIUM, title, description);
  }

  public static Finding high(String title, String description) {
    return new Finding(Severity.HIGH, title, description);
  }
}
