package com.vulnwatch.worker.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.model.RepositoryIntel;
import com.vulnwatch.worker.model.DependencyFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists scan results directly via JDBC (no JPA entity needed for the worker).
 *
 * Expected schema (add to your migrations):
 *
 *   CREATE TABLE dependency_scan_results (
 *     scan_id          UUID PRIMARY KEY,
 *     repo_id          TEXT NOT NULL,
 *     requested_by     TEXT,
 *     ecosystem        TEXT,
 *     total_deps       INT,
 *     vulnerable_count INT,
 *     overall_severity TEXT,
 *     completed_at     TIMESTAMPTZ,
 *     raw_result       JSONB    -- full enriched dep list
 *   );
 */
@Repository
public class RepositoryPersistence {

    private static final Logger log = LoggerFactory.getLogger(RepositoryPersistence.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RepositoryPersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = new ObjectMapper();
    }

    @Transactional
    public void save(RepositoryIntel result) {
        try {
            // { "npm": [...], "maven": [...] }
            String rawJson = mapper.writeValueAsString(result.byEcosystem());
 
            // TEXT[] array literal for Postgres: '{npm,maven}'
            String ecosystemsArray = "{" + String.join(",", result.ecosystems()) + "}";
 
            jdbc.update("""
                INSERT INTO dependency_scan_results
                  (scan_id, repo_id, requested_by, ecosystems,
                   total_deps, vulnerable_count, overall_severity, completed_at, raw_result)
                VALUES (?, ?, ?, ?::text[], ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (scan_id) DO UPDATE SET
                  ecosystems       = EXCLUDED.ecosystems,
                  vulnerable_count = EXCLUDED.vulnerable_count,
                  overall_severity = EXCLUDED.overall_severity,
                  completed_at     = EXCLUDED.completed_at,
                  raw_result       = EXCLUDED.raw_result
                """,
                result.scanId(),
                result.repoId(),
                result.requestedBy(),
                ecosystemsArray,
                result.totalDependencies(),
                result.vulnerableCount(),
                result.overallSeverity(),
                result.completedAt(),
                rawJson
            );
 
            log.debug("Saved scan result for scanId={} ecosystems={}",
                    result.scanId(), result.ecosystems());
 
        } catch (Exception e) {
            throw new RuntimeException("Failed to save scan result for scanId=" + result.scanId(), e);
        }
    }
}
