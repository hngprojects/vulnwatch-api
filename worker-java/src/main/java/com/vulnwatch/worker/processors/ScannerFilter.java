package com.vulnwatch.worker.processors;

import com.vulnwatch.worker.enums.TargetType;
import com.vulnwatch.worker.interfaces.Scanner;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Filters scanners based on the target types requested in a scan job.
 *
 * <p>Since a scan job can request multiple target types (e.g., DOMAIN and REPOSITORY), this class
 * selects all scanners that support ANY of the requested types.
 */
@Slf4j
@Component
public class ScannerFilter {

  /**
   * Filters scanners that support ANY of the target types in the job.
   *
   * <p>For example, if a job requests [DOMAIN, REPOSITORY]:
   *
   * <ul>
   *   <li>DnsScanner (DOMAIN) → included
   *   <li>SslScanner (DOMAIN) → included
   *   <li>DependencyScanner (REPOSITORY) → included
   * </ul>
   *
   * @param allScanners All available scanners (injected by Spring)
   * @param jobTargetTypes Target types requested in the scan job
   * @return List of scanners that support at least one of the requested target types
   */
  public List<Scanner> filterByAnyTargetType(
      List<Scanner> allScanners, List<TargetType> jobTargetTypes) {
    if (jobTargetTypes == null || jobTargetTypes.isEmpty()) {
      log.warn("No target types specified in job, returning empty scanner list");
      return List.of();
    }

    Set<TargetType> targetTypeSet = Set.copyOf(jobTargetTypes);

    List<Scanner> matchingScanners =
        allScanners.stream()
            .filter(scanner -> targetTypeSet.contains(scanner.getTargetType()))
            .toList();

    log.info(
        "Filtered {} scanners matching target types: {}", matchingScanners.size(), jobTargetTypes);

    if (log.isDebugEnabled()) {
      String scannerNames =
          matchingScanners.stream()
              .map(s -> s.getClass().getSimpleName())
              .collect(Collectors.joining(", "));
      log.debug("Matching scanners: {}", scannerNames);
    }

    return matchingScanners;
  }

  /**
   * Filters scanners that support EXACTLY the given target type.
   *
   * <p>Use this when you want to run DOMAIN and REPOSITORY scans separately.
   *
   * @param allScanners All available scanners
   * @param targetType The exact target type to filter by
   * @return List of scanners that support the specified target type
   */
  public List<Scanner> filterByExactTargetType(List<Scanner> allScanners, TargetType targetType) {
    if (targetType == null) {
      log.warn("Target type is null, returning empty scanner list");
      return List.of();
    }

    List<Scanner> matchingScanners =
        allScanners.stream().filter(scanner -> scanner.getTargetType() == targetType).toList();

    log.debug(
        "Filtered {} scanners for exact target type: {}", matchingScanners.size(), targetType);
    return matchingScanners;
  }

  /**
   * Returns the set of target types supported by the given scanners. Useful for debugging and
   * validation.
   */
  public Set<TargetType> getSupportedTargetTypes(List<Scanner> scanners) {
    return scanners.stream().map(Scanner::getTargetType).collect(Collectors.toSet());
  }
}
