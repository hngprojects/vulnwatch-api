package com.vulnwatch.worker.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vulnwatch.worker.model.AiResult;
import com.vulnwatch.worker.model.DomainFinding;
import com.vulnwatch.worker.model.EngineResult;
import com.vulnwatch.worker.model.payload.DnsPayload;
import com.vulnwatch.worker.model.payload.HttpPayload;
import com.vulnwatch.worker.model.payload.SslPayload;

@Repository
public class DomainPersistence {

    private static final Logger log = LoggerFactory.getLogger(DomainPersistence.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private static final String INSERT_FINDING = """
            INSERT INTO "Findings"
            (
                "Id",
                "ScanId",
                "Surface",
                "Severity",
                "Title",
                "CveId",
                "AiExplanation",
                "TechnicalPayload",
                "RemediationSteps",
                "Status",
                "CreatedAt"
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Open', ?)
            """;

    private static final String UPDATE_SCAN = """
            UPDATE "Scans"
            SET
                "Status" = 'Completed',
                "SecurityScore" = ?,
                "CompletedAt" = ?,
                "UpdatedAt" = ?
            WHERE "Id" = ?
            """;

    public DomainPersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = JsonMapper.builder()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .build();
    }

    public List<DomainFinding> saveFindings(
            String scanId,
            List<EngineResult> engineResults,
            List<AiResult> enrichments,
            int securityScore) {

        List<DomainFinding> findings = assembleFindings(scanId, engineResults, enrichments);

        try {

            insertFindings(findings);
            updateScan(scanId, securityScore);

            log.info("Saved {} findings for scan {}", findings.size(), scanId);

        } catch (Exception e) {

            log.error("Failed to save findings for scan {}", scanId, e);

            throw new RuntimeException("Persistence failure", e);
        }

        return findings;
    }

    // ─────────────────────────────────────────────────────────────

    private List<DomainFinding> assembleFindings(
            String scanId,
            List<EngineResult> engineResults,
            List<AiResult> enrichments) {

        List<DomainFinding> findings = new ArrayList<>();

        for (int i = 0; i < engineResults.size(); i++) {

            EngineResult engine = engineResults.get(i);
            AiResult enrichment = i < enrichments.size() ? enrichments.get(i) : null;

            String severity = enrichment != null ? enrichment.severity() : "Low";

            String explanation = enrichment != null
                    ? enrichment.explanation()
                    : "Engine ran but enrichment failed.";

            String cveId = enrichment != null ? enrichment.cveId() : null;

            String remediation =  
                    enrichment != null  
                            && enrichment.remediationSteps() != null  
                            && !enrichment.remediationSteps().isEmpty()  
                            ? String.join("\n", enrichment.remediationSteps())  
                            : "Review engine output manually.";  

            findings.add(new DomainFinding(
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

        if (!engine.success()) {
            return engine.surface() + " probe failed";
        }

        return switch (engine.payload()) {

            case DnsPayload dns -> {

                if (!dns.issues().isEmpty()) {
                    yield dns.issues().get(0);
                }

                yield "DNS scan completed — no issues found";
            }

            case SslPayload ssl -> {

                if (!ssl.issues().isEmpty()) {
                    yield ssl.issues().get(0);
                }

                yield "SSL scan completed — certificate valid";
            }

            case HttpPayload http -> {

                if (!http.missingHeaders().isEmpty()) {
                    yield "Missing security header: "
                            + http.missingHeaders().get(0);
                }

                if (!http.issues().isEmpty()) {
                    yield http.issues().get(0);
                }

                yield "HTTP scan completed — no issues found";
            }

            default -> engine.surface() + " scan completed";
        };
    }

    private String formatPayload(EngineResult engine) {

        if (!engine.success()) {
            try {  
                return mapper.writeValueAsString(  
                        Map.of("error", engine.errorMessage() == null ? "Unknown error" : engine.errorMessage()));  
            } catch (Exception e) {  
                log.warn("Failed to serialize error payload", e);  
                return "{\"error\":\"Unknown error\"}";  
            }  
        }

        try {

            return mapper.writeValueAsString(engine.payload());

        } catch (Exception e) {

            log.warn("Failed to serialize payload", e);

            return "{}";
        }
    }

    private void insertFindings(List<DomainFinding> findings) {

        jdbc.batchUpdate(
                INSERT_FINDING,
                findings,
                findings.size(),
                (ps, f) -> {

                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, UUID.fromString(f.scanId()));
                    ps.setString(3, f.surface());
                    ps.setString(4, f.severity());
                    ps.setString(5, f.title());
                    ps.setString(6, f.cveId());
                    ps.setString(7, f.aiExplanation());
                    ps.setString(8, f.technicalPayload());
                    ps.setString(9, f.remediationSteps());
                    ps.setTimestamp(10, Timestamp.from(Instant.now()));
                });
    }

    private void updateScan(String scanId, int securityScore) {

        Timestamp now = Timestamp.from(Instant.now());

        int updated = jdbc.update(
                UPDATE_SCAN,
                securityScore,
                now,
                now,
                UUID.fromString(scanId));

        if (updated == 0) {
            throw new IllegalStateException("No scan row updated for scanId=" + scanId);
        }
    }
}