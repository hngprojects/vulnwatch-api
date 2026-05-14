package com.vulnwatch.worker.repository;

import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.ScanStatus;
import com.vulnwatch.worker.enums.TargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ScanRepository extends JpaRepository<Scan, UUID> {

  /** Finds all scans for a specific user. */
  List<Scan> findByUserId(UUID userId);

  /** Finds all scans by status. */
  List<Scan> findByStatus(ScanStatus status);

  /** Finds all scans by target type. */
  List<Scan> findByTargetType(TargetType targetType);

  /** Updates scan status to RUNNING with start time. Matches C# MarkRunning() */
  @Modifying
  @Transactional
  @Query("UPDATE Scan s SET s.status = :status, s.startedAt = :startedAt WHERE s.id = :id")
  void markRunning(
      @Param("id") UUID id,
      @Param("status") ScanStatus status,
      @Param("startedAt") Instant startedAt);

  /**
   * Updates scan status to COMPLETED with security score. Matches C# Complete(int securityScore)
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE Scan s SET s.status = :status, s.securityScore = :score, s.completedAt = :completedAt WHERE s.id = :id")
  void markCompleted(
      @Param("id") UUID id,
      @Param("status") ScanStatus status,
      @Param("score") int score,
      @Param("completedAt") Instant completedAt);

  /** Updates scan status to FAILED. Matches C# Fail() */
  @Modifying
  @Transactional
  @Query("UPDATE Scan s SET s.status = :status, s.completedAt = :completedAt WHERE s.id = :id")
  void markFailed(
      @Param("id") UUID id,
      @Param("status") ScanStatus status,
      @Param("completedAt") Instant completedAt);

  /** Updates only the status field. */
  @Modifying
  @Transactional
  @Query("UPDATE Scan s SET s.status = :status WHERE s.id = :id")
  void updateStatus(@Param("id") UUID id, @Param("status") ScanStatus status);

  /** Counts scans by status for a user. */
  long countByUserIdAndStatus(UUID userId, ScanStatus status);
}
