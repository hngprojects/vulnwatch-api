package com.vulnwatch.worker.persistence;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vulnwatch.worker.config.DbConfig;
import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.AiResult;
import com.vulnwatch.worker.model.Finding;
import com.vulnwatch.worker.model.payload.DnsPayload;
import com.vulnwatch.worker.model.payload.HttpPayload;
import com.vulnwatch.worker.model.payload.SslPayload;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FindingPersistenceService {

    private final ObjectMapper mapper = JsonMapper.builder()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .build();
    private static final String INSERT_FINDING = """
            INSERT INTO "Findings" ("Id", "ScanId", "Surface", "Severity", "Title", "CveId",
                "AiExplanation", "TechnicalPayload", "RemediationSteps", "Status", "CreatedAt")
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Open', ?)
            """;

    private static final String UPDATE_SCAN = """
            UPDATE "Scans"
            SET "Status" = 'Completed', "SecurityScore" = ?, "CompletedAt" = ?, "UpdatedAt" = ?
            WHERE "Id" = ?
            """;

    /**
     * Assembles Finding records from engine + enrichment pairs, persists them,
     * and marks the scan completed — all in a single transaction.
     */
    public List<Finding> saveFindings(
            String scanId,
            List<EngineResult> engineResults,
            List<AiResult> enrichments,
            int securityScore) {

        List<Finding> findings = assemblefindings(scanId, engineResults, enrichments);

        try (Connection conn = DbConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertFindings(conn, findings);
                updateScan(conn, scanId, securityScore);
                conn.commit();
                System.out.printf("[DB] Saved %d findings for scan %s%n",
                        findings.size(), scanId);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            System.err.println("[DB] Failed to save findings: " + e.getMessage());
            e.printStackTrace();
        }

        return findings;
    }

    // ── private ─────────────────────────────────────────────────────────────

    private List<Finding> assemblefindings(
            String scanId,
            List<EngineResult> engineResults,
            List<AiResult> enrichments) {

        // zip by index — engines and enrichments are produced in the same order
        java.util.ArrayList<Finding> findings = new java.util.ArrayList<>();
        for (int i = 0; i < engineResults.size(); i++) {
            EngineResult engine = engineResults.get(i);
            AiResult enrichment = i < enrichments.size() ? enrichments.get(i) : null;

            String severity = enrichment != null ? enrichment.severity() : "Low";
            String explanation = enrichment != null ? enrichment.explanation() : "Engine ran but enrichment failed.";
            String cveId = enrichment != null ? enrichment.cveId() : null;
            String remediation = enrichment != null
                    ? String.join("\n", enrichment.remediationSteps())
                    : "Review engine output manually.";

            findings.add(new Finding(
                    scanId,
                    engine.surface(),
                    severity,
                    buildTitle(engine),
                    cveId,
                    explanation,
                    formatPayload(engine),
                    remediation));
        }
        return findings;
    }

    private String buildTitle(EngineResult engine) {
        if (!engine.success())
            return engine.surface() + " probe failed";

        return switch (engine.payload()) {
            case DnsPayload dns -> {
                if (!dns.issues().isEmpty())
                    yield dns.issues().get(0);
                yield "DNS scan completed — no issues found";
            }
            case SslPayload ssl -> {
                if (!ssl.issues().isEmpty())
                    yield ssl.issues().get(0);
                yield "SSL scan completed — certificate valid";
            }
            case HttpPayload http -> {
                if (!http.missingHeaders().isEmpty())
                    yield "Missing security header: " + http.missingHeaders().get(0);
                if (!http.issues().isEmpty())
                    yield http.issues().get(0);
                yield "HTTP scan completed — no issues found";
            }
            default -> engine.surface() + " scan completed";
        };
    }

    private String formatPayload(EngineResult engine) {
        if (!engine.success())
            return "{\"error\": \"" + engine.errorMessage() + "\"}";
        try {
            return mapper.writeValueAsString(engine.payload());
        } catch (Exception e) {
            return "{}";
        }
    }

    private void insertFindings(Connection conn, List<Finding> findings) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_FINDING)) {
            for (Finding f : findings) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setObject(2, UUID.fromString(f.scanId()));
                stmt.setString(3, f.surface());
                stmt.setString(4, f.severity());
                stmt.setString(5, f.title());
                stmt.setString(6, f.cveId());
                stmt.setString(7, f.aiExplanation());
                stmt.setString(8, f.technicalPayload());
                stmt.setString(9, f.remediationSteps());
                stmt.setTimestamp(10, Timestamp.from(Instant.now()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void updateScan(Connection conn, String scanId, int securityScore) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SCAN)) {
            Timestamp now = Timestamp.from(Instant.now());
            stmt.setInt(1, securityScore);
            stmt.setTimestamp(2, now);
            stmt.setTimestamp(3, now);
            stmt.setObject(4, UUID.fromString(scanId));
            stmt.executeUpdate();
        }
    }
}