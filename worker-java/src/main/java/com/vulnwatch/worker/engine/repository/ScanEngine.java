package com.vulnwatch.worker.engine.repository;

import java.util.List;

/**
 * Contract for all ecosystem scanners.
 *
 * To add a new ecosystem (Maven, NuGet, pip, etc.):
 *   1. Implement this interface
 *   2. Annotate with @Component("<manifest-filename>")
 *      e.g. @Component("pom.xml"), @Component("requirements.txt")
 *   3. Spring auto-registers it into Map<String, DependencyScanner> in ProcessorConfig
 *
 * No changes needed in RepositoryJobProcessor.
 */
public interface ScanEngine {

    // returns "npm", "maven", "pip", etc.
    String ecosystem();

    /**
     * The manifest filename this scanner handles (e.g. "package.json", "pom.xml").
     * Must match the @Component name on the implementation.
     */
    String manifestFilename();

    /**
     * Given the full file path list from the repo, returns the path to the manifest.
     * Usually finds the root-level manifest, but can be customised per ecosystem.
     */
    String findManifest(List<String> filePaths);

    /**
     * Parses the raw manifest content and returns a list of dependency strings.
     * Format is up to the scanner — the AI enrichment step handles interpretation.
     * Recommended format: "name@version" e.g. "lodash@4.17.21"
     */
    List<String> parseDependencies(String manifestContent);
}
