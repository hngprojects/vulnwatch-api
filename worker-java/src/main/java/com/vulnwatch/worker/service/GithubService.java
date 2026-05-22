package com.vulnwatch.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Talks to the GitHub REST API.
 *
 * Required env vars:
 * GITHUB_TOKEN — personal access token or GitHub App installation token
 * GITHUB_API_URL — defaults to https://api.github.com (override for GitHub
 * Enterprise)
 */
@Service
public class GithubService {

    private static final Logger log = LoggerFactory.getLogger(GithubService.class);

    private final String token;
    private final String apiUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GithubService(
            @Value("${github.token}") String token,
            @Value("${github.api-url:https://api.github.com}") String apiUrl) {

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing github.token configuration");
        }
        
        this.token = token;
        this.apiUrl = apiUrl;
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Returns all file paths in the repo using the Git Trees API (recursive).
     *
     * @param repoId format: "owner/repo" e.g. "tonykoder/my-app"
     */
    public List<String> getFilePaths(String repoId) {
        try {
            // First get the default branch SHA
            String repoUrl = apiUrl + "/repos/" + repoId;
            JsonNode repoInfo = get(repoUrl);
            String defaultBranch = repoInfo.get("default_branch").asText();
            // String sha = repoInfo.get("branches_url").asText(); // placeholder — get
            // actual SHA below

            // Get branch SHA
            String branchUrl = apiUrl + "/repos/" + repoId + "/branches/" + defaultBranch;
            JsonNode branchInfo = get(branchUrl);
            String treeSha = branchInfo.at("/commit/commit/tree/sha").asText();

            // Get full recursive file tree
            String treeUrl = apiUrl + "/repos/" + repoId + "/git/trees/" + treeSha + "?recursive=1";
            JsonNode tree = get(treeUrl);

            List<String> paths = new ArrayList<>();
            for (JsonNode item : tree.get("tree")) {
                if ("blob".equals(item.get("type").asText())) {
                    paths.add(item.get("path").asText());
                }
            }

            log.debug("Fetched {} file paths from {}", paths.size(), repoId);
            return paths;

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch file tree for repo: " + repoId, e);
        }
    }

    /**
     * Fetches the decoded content of a single file.
     *
     * @param repoId   format: "owner/repo"
     * @param filePath path within the repo e.g. "package.json"
     */
    public String getFileContent(String repoId, String filePath) {
        try {
            String url = apiUrl + "/repos/" + repoId + "/contents/" + filePath;
            JsonNode response = get(url);

            String encoding = response.get("encoding").asText();
            String content = response.get("content").asText().replaceAll("\\s", ""); // strip newlines

            if ("base64".equals(encoding)) {
                return new String(Base64.getDecoder().decode(content));
            }

            return content;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch file: " + filePath + " from " + repoId, e);
        }
    }

    // ── Internal HTTP helper ─────────────────────────────────────────────────

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API error " + response.statusCode() + ": " + response.body());
        }

        return mapper.readTree(response.body());
    }
}
