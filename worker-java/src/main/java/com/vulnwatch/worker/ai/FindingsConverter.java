package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.FindingStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.exception.InvalidSurfaceTypeException;
import com.vulnwatch.worker.models.ai.AiFinding;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Converts AI response findings to database Finding entities. Handles enum conversion with severity
 * fallback only. Surface type MUST be valid - no fallback to INFO.
 */
@Slf4j
@Component
public class FindingsConverter {

  private static final int MAX_FINDINGS_PER_SCAN = 20;

  /**
   * Converts a list of AI findings to database Finding entities. Limits the number of findings to
   * prevent data overload.
   *
   * @param scanId The ID of the scan these findings belong to
   * @param aiFindings List of AI-generated findings
   * @return List of database-ready Finding entities
   * @throws IllegalArgumentException if any finding has an invalid surface type
   */
  public List<Finding> convertToFindings(UUID scanId, List<AiFinding> aiFindings) {
    if (aiFindings == null || aiFindings.isEmpty()) {
      log.warn("No AI findings to convert for scan: {}", scanId);
      return List.of();
    }

    return aiFindings.stream()
        .limit(MAX_FINDINGS_PER_SCAN)
        .map(aiFinding -> convertSingleFinding(scanId, aiFinding))
        .toList();
  }

  /**
   * Converts a single AI finding to a database Finding entity.
   *
   * @param scanId The ID of the scan
   * @param aiFinding The AI-generated finding
   * @return Database-ready Finding entity
   * @throws IllegalArgumentException if surface type is invalid
   */
  private Finding convertSingleFinding(UUID scanId, AiFinding aiFinding) {
    return Finding.builder()
        .id(UUID.randomUUID())
        .scanId(scanId)
        .surface(parseSurface(aiFinding.getSurface()))
        .severity(parseSeverity(aiFinding.getSeverity()))
        .title(truncate(aiFinding.getTitle(), 500))
        .aiExplanation(truncate(aiFinding.getPlainEnglishExplanation(), 2000))
        .technicalDetails(truncate(aiFinding.getTechnicalDetails(), 2000))
        .remediationSteps(
            truncate(
                aiFinding.getRemediationSteps() != null
                    ? String.join("\n", aiFinding.getRemediationSteps())
                    : null,
                4000))
        .status(FindingStatus.OPEN)
        .build();
  }

  /**
   * Parses severity string to FindingSeverity enum. Defaults to MEDIUM if value is unknown or null.
   *
   * @param severityStr Severity string from AI (Critical, High, Medium, Low)
   * @return FindingSeverity enum value
   */
  private FindingSeverity parseSeverity(String severityStr) {
    if (severityStr == null) {
      log.debug("Null severity value, defaulting to MEDIUM");
      return FindingSeverity.MEDIUM;
    }

    try {
      return FindingSeverity.valueOf(severityStr.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unknown severity: '{}', defaulting to MEDIUM", severityStr);
      return FindingSeverity.MEDIUM;
    }
  }

  /**
   * Parses surface string to SurfaceType enum. NO FALLBACK - uses SurfaceType.fromString() which
   * throws exception for invalid values.
   *
   * @param surfaceStr Surface string from AI (DNS, SSL, HTTP_HEADERS, etc.)
   * @return SurfaceType enum value
   * @throws IllegalArgumentException if surface value is invalid or null
   */
  private SurfaceType parseSurface(String surfaceStr) {
    try {
      return SurfaceType.fromString(surfaceStr);
    } catch (InvalidSurfaceTypeException e) {
      return SurfaceType.INFO;
    }
  }

  /**
   * Truncates a string to the specified maximum length. Prevents database column overflow.
   *
   * @param text The text to truncate
   * @param maxLength Maximum allowed length
   * @return Truncated text with ellipsis if needed
   */
  private String truncate(String text, int maxLength) {
    if (text == null) {
      return null;
    }
    if (text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength - 3) + "...";
  }
}
