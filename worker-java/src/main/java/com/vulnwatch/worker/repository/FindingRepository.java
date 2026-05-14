package com.vulnwatch.worker.repository;

import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.enums.FindingSeverity;
import com.vulnwatch.worker.enums.FindingStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface FindingRepository extends JpaRepository<Finding, UUID> {

  /** Finds all findings for a specific scan. */
  List<Finding> findByScanId(UUID scanId);

  /** Finds all findings by severity. */
  List<Finding> findBySeverity(FindingSeverity severity);

  /** Finds all findings by surface type. */
  List<Finding> findBySurface(SurfaceType surface);

  /** Finds all findings by status. */
  List<Finding> findByStatus(FindingStatus status);

  /** Finds all findings for a scan with a specific severity. */
  List<Finding> findByScanIdAndSeverity(UUID scanId, FindingSeverity severity);

  /** Finds all findings for a scan with a specific status. */
  List<Finding> findByScanIdAndStatus(UUID scanId, FindingStatus status);

  /** Updates the status of a finding (OPEN → REMEDIATED/IGNORED). */
  @Modifying
  @Transactional
  @Query("UPDATE Finding f SET f.status = :status WHERE f.id = :id")
  public void updateStatus(@Param("id") UUID id, @Param("status") FindingStatus status);

  /** Bulk updates status for all findings of a scan. */
  @Modifying
  @Transactional
  @Query("UPDATE Finding f SET f.status = :status WHERE f.scanId = :scanId")
  public void updateStatusByScanId(
      @Param("scanId") UUID scanId, @Param("status") FindingStatus status);
}
