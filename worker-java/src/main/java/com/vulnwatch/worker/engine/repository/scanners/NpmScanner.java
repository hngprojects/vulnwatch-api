package com.vulnwatch.worker.engine.repository.scanners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnwatch.worker.engine.repository.ScanEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans npm projects via package.json.
 *
 * Registered as "package.json" so RepositoryJobProcessor can resolve it
 * when it finds that filename in the repo file tree.
 */
@Component("package.json")
public class NpmScanner implements ScanEngine {

    private static final Logger log = LoggerFactory.getLogger(NpmScanner.class);
    private static final String MANIFEST = "package.json";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String ecosystem() { return "npm"; }

    @Override
    public String manifestFilename() {
        return MANIFEST;
    }

    @Override
    public String findManifest(List<String> filePaths) {
        // Prefer root-level package.json; fall back to first match
        return filePaths.stream()
                .filter(p -> p.equals(MANIFEST) || p.endsWith("/" + MANIFEST))
                .min((a, b) -> {
                    // fewer slashes = closer to root
                    int depthA = (int) a.chars().filter(c -> c == '/').count();
                    int depthB = (int) b.chars().filter(c -> c == '/').count();
                    return Integer.compare(depthA, depthB);
                })
                .orElseThrow(() -> new IllegalStateException("package.json not found in file list"));
    }

    @Override
    public List<String> parseDependencies(String manifestContent) {
        List<String> deps = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(manifestContent);

            // Collect both dependencies and devDependencies
            deps.addAll(extractDeps(root, "dependencies"));
            deps.addAll(extractDeps(root, "devDependencies"));

            log.debug("Parsed {} npm dependencies", deps.size());
        } catch (Exception e) {
            log.error("Failed to parse package.json: {}", e.getMessage());
            throw new RuntimeException("package.json parse error", e);
        }
        return deps;
    }

    /**
     * Extracts deps from a given section as "name@version" strings.
     * e.g. { "lodash": "^4.17.21" } → "lodash@^4.17.21"
     */
    private List<String> extractDeps(JsonNode root, String section) {
        List<String> result = new ArrayList<>();
        JsonNode node = root.get(section);
        if (node == null || node.isNull()) return result;

        node.fields().forEachRemaining(entry ->
                result.add(entry.getKey() + "@" + entry.getValue().asText()));

        return result;
    }
}