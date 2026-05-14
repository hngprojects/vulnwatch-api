package com.vulnwatch.worker.ai;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.models.ai.AiFinding;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Calculates and validates security scores for scan results. Uses hybrid approach: merges
 * AI-provided score with rule-based calculation.
 *
 * <p>This component has NO external dependencies (no Redis, no Database, no HTTP). It is purely a
 * calculator
 */
@Slf4j
@Component
public class ScoreCalculator {

  private static final double AI_WEIGHT = 0.6;
  private static final double RULE_WEIGHT = 0.4;
  private static final int ANOMALY_THRESHOLD = 30;

  /**
   * Calculates final security score by merging AI score with rule-based calculation.
   *
   * @param aiScore Score from AI (0-100), can be null
   * @param findings List of AI-generated findings
   * @return Final security score clamped between 0 and 100
   */
  public int calculateFinalScore(Integer aiScore, List<AiFinding> findings) {
    int ruleScore = calculateFromFindings(findings);

    if (aiScore == null) {
      log.debug("No AI score provided, using rule-based score: {}", ruleScore);
      return ruleScore;
    }

    int validatedAiScore = clamp(aiScore);
    int difference = Math.abs(validatedAiScore - ruleScore);

    if (difference > ANOMALY_THRESHOLD) {
      log.warn(
          "AI score ({}) differs significantly from rule score ({}). Difference: {}",
          validatedAiScore,
          ruleScore,
          difference);

      return calculateWeightedScore(validatedAiScore, ruleScore, 0.3, 0.7);
    }

    return calculateWeightedScore(validatedAiScore, ruleScore, AI_WEIGHT, RULE_WEIGHT);
  }

  /**
   * Calculates score from findings using severity-based penalties.
   *
   * @param findings List of AI-generated findings
   * @return Rule-based security score (0-100)
   */
  public int calculateFromFindings(List<AiFinding> findings) {
    if (findings == null || findings.isEmpty()) {
      return 100;
    }

    int score = 100;
    for (AiFinding finding : findings) {
      score -= getPenaltyForSeverity(finding.getSeverity());
    }
    return clamp(score);
  }

  /**
   * Calculates score from database Finding entities.
   *
   * @param findings List of database Finding entities
   * @return Rule-based security score (0-100)
   */
  public int calculateFromDbFindings(List<Finding> findings) {
    if (findings == null || findings.isEmpty()) {
      return 100;
    }

    int score = 100;
    for (Finding finding : findings) {
      score -= getPenaltyForSeverityEnum(finding.getSeverity());
    }
    return clamp(score);
  }

  /**
   * Returns penalty points for a severity string.
   *
   * @param severity Severity string (Critical, High, Medium, Low)
   * @return Penalty points to subtract from score
   */
  private int getPenaltyForSeverity(String severity) {
    if (severity == null) {
      log.debug("Null severity, applying default penalty of 2");
      return 2;
    }

    return switch (severity.toUpperCase()) {
      case "CRITICAL" -> 15;
      case "HIGH" -> 8;
      case "MEDIUM" -> 3;
      case "LOW" -> 1;
      default -> {
        log.warn("Unknown severity: '{}', applying default penalty of 2", severity);
        yield 2;
      }
    };
  }

  /**
   * Returns penalty points for a FindingSeverity enum.
   *
   * @param severity FindingSeverity enum value
   * @return Penalty points to subtract from score
   */
  private int getPenaltyForSeverityEnum(FindingSeverity severity) {
    if (severity == null) {
      return 2;
    }

    return switch (severity) {
      case CRITICAL -> 15;
      case HIGH -> 8;
      case MEDIUM -> 3;
      case LOW -> 1;
      default -> 2;
    };
  }

  /**
   * Calculates weighted average of two scores.
   *
   * @param score1 First score
   * @param score2 Second score
   * @param weight1 Weight for first score (0.0 to 1.0)
   * @param weight2 Weight for second score (0.0 to 1.0)
   * @return Weighted score clamped to 0-100
   */
  private int calculateWeightedScore(int score1, int score2, double weight1, double weight2) {
    double weighted = (score1 * weight1) + (score2 * weight2);
    return clamp((int) Math.round(weighted));
  }

  /**
   * Clamps a score to the valid range of 0-100.
   *
   * @param score Raw score value
   * @return Clamped score between 0 and 100 inclusive
   */
  private int clamp(int score) {
    if (score < 0) {
      log.debug("Score {} below 0, clamping to 0", score);
      return 0;
    }
    if (score > 100) {
      log.debug("Score {} above 100, clamping to 100", score);
      return 100;
    }
    return score;
  }
}
