package com.keplerops.groundcontrol.infrastructure.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GitHubCliClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubCliClient.class);
    private static final long TIMEOUT_SECONDS = 60;
    private static final int PAGE_SIZE = 100;
    private static final Pattern ISSUE_URL_RE = Pattern.compile("/issues/(\\d+)$");

    /** GitHub owner and repo names: alphanumeric, hyphens, dots, underscores; starts with alphanumeric. */
    private static final Pattern GITHUB_NAME_RE = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");

    /** GitHub owner/repo combined format (e.g. "KeplerOps/Ground-Control"). */
    private static final Pattern GITHUB_REPO_RE =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}/[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");

    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_BODY_LENGTH = 65_536;
    public static final int MAX_LABEL_LENGTH = 50;
    private static final Pattern LABEL_RE = Pattern.compile("^[a-zA-Z0-9 :._-]+$");

    private final ObjectMapper objectMapper;
    private final String ghPath;

    public GitHubCliClient(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${groundcontrol.github.gh-path:}") String ghPath) {
        this.objectMapper = objectMapper;
        this.ghPath = ghPath.isBlank() ? resolveGhPath() : ghPath;
        log.info("github_cli_path: {}", this.ghPath);
    }

    // Common Linux/macOS install locations; Windows is not supported.
    private static String resolveGhPath() {
        for (String candidate : List.of("/usr/bin/gh", "/usr/local/bin/gh", "/opt/homebrew/bin/gh")) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(candidate))) {
                return candidate;
            }
        }
        return "gh";
    }

    public record IssuePage(List<GitHubIssueData> issues, int rawCount) {
        public IssuePage {
            issues = List.copyOf(issues);
        }
    }

    public static void validateOwnerRepo(String owner, String repo) {
        if (owner == null || !GITHUB_NAME_RE.matcher(owner).matches()) {
            throw new GroundControlException(
                    "Invalid GitHub owner: must be alphanumeric with hyphens/dots/underscores", "invalid_github_owner");
        }
        if (repo == null || !GITHUB_NAME_RE.matcher(repo).matches()) {
            throw new GroundControlException(
                    "Invalid GitHub repo name: must be alphanumeric with hyphens/dots/underscores",
                    "invalid_github_repo");
        }
    }

    public static void validateRepoSlug(String repo) {
        if (repo == null || !GITHUB_REPO_RE.matcher(repo).matches()) {
            throw new GroundControlException(
                    "Invalid GitHub repo: must match 'owner/repo' format", "invalid_github_repo");
        }
    }

    public static void validateIssueContent(String title, String body, List<String> labels) {
        if (title == null || title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
            throw new GroundControlException(
                    "Issue title must be non-blank and at most " + MAX_TITLE_LENGTH + " characters",
                    "invalid_issue_title");
        }
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            throw new GroundControlException(
                    "Issue body must be at most " + MAX_BODY_LENGTH + " characters", "invalid_issue_body");
        }
        if (labels != null) {
            for (String label : labels) {
                if (label == null
                        || label.length() > MAX_LABEL_LENGTH
                        || !LABEL_RE.matcher(label).matches()) {
                    throw new GroundControlException(
                            "Invalid label: must be alphanumeric with spaces/colons/hyphens, max " + MAX_LABEL_LENGTH
                                    + " chars",
                            "invalid_issue_label");
                }
            }
        }
    }

    @Override
    public List<GitHubIssueData> fetchAllIssues(String owner, String repo) {
        validateOwnerRepo(owner, repo);
        List<GitHubIssueData> allIssues = new ArrayList<>();
        int page = 1;

        while (true) {
            IssuePage batch = fetchIssuePage(owner, repo, page);
            allIssues.addAll(batch.issues());

            if (batch.rawCount() < PAGE_SIZE) {
                break;
            }

            log.info(
                    "github_issues_page_full: page={} raw={} issues={} repo={}/{}, fetching next page",
                    page,
                    batch.rawCount(),
                    batch.issues().size(),
                    owner,
                    repo);
            page++;
        }

        log.info("github_issues_fetched: count={} pages={} repo={}/{}", allIssues.size(), page, owner, repo);
        return allIssues;
    }

    protected IssuePage fetchIssuePage(String owner, String repo, int page) {
        String stdout = execGh(List.of(
                ghPath,
                "api",
                String.format("repos/%s/%s/issues", owner, repo),
                "--method",
                "GET",
                "-f",
                "state=all",
                "-f",
                "per_page=" + PAGE_SIZE,
                "-f",
                "page=" + page));

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawIssues =
                    objectMapper.readValue(stdout, new TypeReference<List<Map<String, Object>>>() {});
            return new IssuePage(parseIssues(rawIssues), rawIssues.size());
        } catch (IOException e) {
            throw new GroundControlException("Failed to parse gh CLI output: " + e.getMessage(), "github_parse_error");
        }
    }

    @SuppressWarnings("unchecked")
    public static List<GitHubIssueData> parseIssues(List<Map<String, Object>> rawIssues) {
        List<GitHubIssueData> result = new ArrayList<>();
        for (Map<String, Object> raw : rawIssues) {
            // GitHub REST API returns PRs in the issues endpoint; skip them
            if (raw.containsKey("pull_request")) {
                continue;
            }

            int number = ((Number) raw.get("number")).intValue();
            String title = (String) raw.get("title");
            String apiState = (String) raw.get("state");
            String state = apiState.equalsIgnoreCase("open") ? "OPEN" : "CLOSED";
            String url = (String) raw.get("html_url");
            String body = raw.get("body") != null ? (String) raw.get("body") : "";

            List<Map<String, Object>> labelObjects =
                    raw.get("labels") != null ? (List<Map<String, Object>>) raw.get("labels") : List.of();

            List<String> labels = new ArrayList<>();
            for (Map<String, Object> labelObj : labelObjects) {
                labels.add((String) labelObj.get("name"));
            }

            result.add(new GitHubIssueData(number, title, state, url, body, labels));
        }
        return result;
    }

    @Override
    public GitHubIssueData createIssue(String repo, String title, String body, List<String> labels) {
        validateRepoSlug(repo);
        validateIssueContent(title, body, labels);

        List<String> args =
                new ArrayList<>(List.of(ghPath, "issue", "create", "--repo", repo, "--title", title, "--body", body));
        if (labels != null && !labels.isEmpty()) {
            args.add("--label");
            args.add(String.join(",", labels));
        }

        String stdout = execGh(args);
        String url = stdout.trim();

        Matcher matcher = ISSUE_URL_RE.matcher(url);
        if (!matcher.find()) {
            throw new GroundControlException(
                    "Could not parse issue number from gh output: " + url, "github_parse_error");
        }
        int number = Integer.parseInt(matcher.group(1));

        log.info("github_issue_created: number={} repo={}", number, repo);
        return new GitHubIssueData(number, title, "OPEN", url, body, labels != null ? labels : List.of());
    }

    // -----------------------------------------------------------------------
    // Pull request fetching
    // -----------------------------------------------------------------------

    public record PrPage(List<GitHubPullRequestData> prs, int rawCount) {
        public PrPage {
            prs = List.copyOf(prs);
        }
    }

    @Override
    public List<GitHubPullRequestData> fetchAllPullRequests(String owner, String repo) {
        validateOwnerRepo(owner, repo);
        List<GitHubPullRequestData> allPrs = new ArrayList<>();
        int page = 1;

        while (true) {
            PrPage batch = fetchPrPage(owner, repo, page);
            allPrs.addAll(batch.prs());

            if (batch.rawCount() < PAGE_SIZE) {
                break;
            }

            log.info(
                    "github_prs_page_full: page={} raw={} prs={} repo={}/{}, fetching next page",
                    page,
                    batch.rawCount(),
                    batch.prs().size(),
                    owner,
                    repo);
            page++;
        }

        log.info("github_prs_fetched: count={} pages={} repo={}/{}", allPrs.size(), page, owner, repo);
        return allPrs;
    }

    @Override
    public GitHubPullRequestData fetchPullRequest(String owner, String repo, int number) {
        validateOwnerRepo(owner, repo);
        if (number < 1) {
            throw new GroundControlException("Pull request number must be a positive integer", "invalid_pr_number");
        }
        String stdout = execGh(
                List.of(ghPath, "api", String.format("repos/%s/%s/pulls/%d", owner, repo, number), "--method", "GET"));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(stdout, new TypeReference<Map<String, Object>>() {});
            // Reuse the list parser so the single-PR shape and the list shape decode identically; the
            // single-PR endpoint carries the same number/state/merged_at/base/head fields.
            return parsePullRequests(List.of(raw)).get(0);
        } catch (IOException e) {
            throw new GroundControlException(
                    "Failed to parse gh CLI PR output: " + e.getMessage(), "github_parse_error");
        }
    }

    protected PrPage fetchPrPage(String owner, String repo, int page) {
        String stdout = execGh(List.of(
                ghPath,
                "api",
                String.format("repos/%s/%s/pulls", owner, repo),
                "--method",
                "GET",
                "-f",
                "state=all",
                "-f",
                "per_page=" + PAGE_SIZE,
                "-f",
                "page=" + page));

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawPrs =
                    objectMapper.readValue(stdout, new TypeReference<List<Map<String, Object>>>() {});
            return new PrPage(parsePullRequests(rawPrs), rawPrs.size());
        } catch (IOException e) {
            throw new GroundControlException(
                    "Failed to parse gh CLI PR output: " + e.getMessage(), "github_parse_error");
        }
    }

    @SuppressWarnings("unchecked")
    public static List<GitHubPullRequestData> parsePullRequests(List<Map<String, Object>> rawPrs) {
        List<GitHubPullRequestData> result = new ArrayList<>();
        for (Map<String, Object> raw : rawPrs) {
            int number = ((Number) raw.get("number")).intValue();
            String title = (String) raw.get("title");
            String apiState = (String) raw.get("state");
            String state = apiState.equalsIgnoreCase("open") ? "OPEN" : "CLOSED";
            boolean merged = raw.get("merged_at") != null;
            String url = (String) raw.get("html_url");
            String body = raw.get("body") != null ? (String) raw.get("body") : "";

            Map<String, Object> baseObj = (Map<String, Object>) raw.get("base");
            String baseBranch = baseObj != null ? (String) baseObj.get("ref") : "";
            Map<String, Object> headObj = (Map<String, Object>) raw.get("head");
            String headBranch = headObj != null ? (String) headObj.get("ref") : "";

            List<Map<String, Object>> labelObjects =
                    raw.get("labels") != null ? (List<Map<String, Object>>) raw.get("labels") : List.of();

            List<String> labels = new ArrayList<>();
            for (Map<String, Object> labelObj : labelObjects) {
                labels.add((String) labelObj.get("name"));
            }

            result.add(
                    new GitHubPullRequestData(number, title, state, merged, url, body, baseBranch, headBranch, labels));
        }
        return result;
    }

    /**
     * Execute a gh CLI command, draining stdout/stderr concurrently to avoid pipe-buffer deadlock.
     */
    private String execGh(List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            var stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            var stderrFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getErrorStream().readAllBytes();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GroundControlException("gh CLI timed out after " + TIMEOUT_SECONDS + "s", "github_timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(stderrFuture.join(), StandardCharsets.UTF_8);
                throw new GroundControlException(
                        "gh CLI exited with code " + exitCode + ": " + stderr, "github_cli_error");
            }

            return new String(stdoutFuture.join(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GroundControlException("Failed to execute gh CLI: " + e.getMessage(), "github_cli_error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroundControlException("gh CLI execution interrupted", "github_interrupted", e);
        }
    }
}
