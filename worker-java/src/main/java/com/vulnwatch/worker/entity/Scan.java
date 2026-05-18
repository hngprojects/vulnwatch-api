package com.vulnwatch.worker.entity;

import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.TargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Scan entity - matches the 'scans' table in PostgreSQL. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scans")
@Schema(description = "Scan entity - matches the 'scans' table in PostgreSQL")
public class Scan {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Schema(description = "Primary key", example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID id;

  @Column(nullable = false)
  @Schema(description = "ID of the user who requested the scan")
  private UUID userId;

  @Column(unique = true, nullable = false)
  @Schema(description = "Idempotency key to prevent duplicate processing")
  private UUID idempotencyKey;

  @Column(nullable = false)
  @Schema(description = "Type of target scanned", example = "Domain")
  private TargetType targetType;

  @Schema(description = "Domain ID (if targetType=DOMAIN)")
  private UUID domainId;

  @Schema(description = "Repository ID (if targetType=REPOSITORY)")
  private Long repoId;

  @Column(nullable = false)
  @Schema(description = "Scan status", example = "Queued")
  private ScanStatus status;

  @Schema(description = "Security score 0-100", minimum = "0", maximum = "100")
  private Integer securityScore;

  @Schema(description = "When the scan started")
  private Instant startedAt;

  @Schema(description = "When the scan completed")
  private Instant completedAt;

  public void markRunning() {
    this.status = ScanStatus.RUNNING;
    this.startedAt = Instant.now();
  }

  public void markCompleted(int score) {
    this.status = ScanStatus.COMPLETED;
    this.securityScore = score;
    this.completedAt = Instant.now();
  }

  public boolean isTerminal() {
    return status == ScanStatus.FAILED || status == ScanStatus.COMPLETED;
  }

  public void markFailed() {
    this.status = ScanStatus.FAILED;
    this.completedAt = Instant.now();
  }

  public boolean isDomainScan() {
    return targetType == TargetType.DOMAIN;
  }

  public boolean isRepositoryScan() {
    return targetType == TargetType.REPOSITORY;
  }
}