package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.infrastructure.github.GitHubCliClient;
import com.keplerops.groundcontrol.infrastructure.github.GitHubCliClient.IssuePage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubCliClientTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class RestApiJsonParsing {

        @Test
        void parsesValidRestApiJsonOutput() throws Exception {
            String json =
                    """
                    [
                      {
                        "number": 42,
                        "title": "Fix login bug",
                        "state": "open",
                        "html_url": "https://github.com/o/r/issues/42",
                        "body": "Login is broken for #10",
                        "labels": [{"name": "bug"}, {"name": "P0"}]
                      }
                    ]
                    """;

            List<GitHubIssueData> result = parseJson(json);

            assertThat(result).hasSize(1);
            GitHubIssueData issue = result.get(0);
            assertThat(issue.number()).isEqualTo(42);
            assertThat(issue.title()).isEqualTo("Fix login bug");
            assertThat(issue.state()).isEqualTo("OPEN");
            assertThat(issue.url()).isEqualTo("https://github.com/o/r/issues/42");
            assertThat(issue.body()).isEqualTo("Login is broken for #10");
            assertThat(issue.labels()).containsExactly("bug", "P0");
        }

        @Test
        void mapsClosedStateToUpperCase() throws Exception {
            String json =
                    """
                    [
                      {
                        "number": 1,
                        "title": "Closed issue",
                        "state": "closed",
                        "html_url": "https://github.com/o/r/issues/1",
                        "body": null,
                        "labels": []
                      }
                    ]
                    """;

            List<GitHubIssueData> result = parseJson(json);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).state()).isEqualTo("CLOSED");
            assertThat(result.get(0).body()).isEmpty();
        }

        @Test
        void filtersPullRequests() throws Exception {
            String json =
                    """
                    [
                      {
                        "number": 1,
                        "title": "Real issue",
                        "state": "open",
                        "html_url": "https://github.com/o/r/issues/1",
                        "body": "",
                        "labels": []
                      },
                      {
                        "number": 2,
                        "title": "A pull request",
                        "state": "open",
                        "html_url": "https://github.com/o/r/pull/2",
                        "body": "",
                        "labels": [],
                        "pull_request": {"url": "https://api.github.com/repos/o/r/pulls/2"}
                      }
                    ]
                    """;

            List<GitHubIssueData> result = parseJson(json);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).number()).isEqualTo(1);
        }

        @Test
        void extractsLabelNames() throws Exception {
            String json =
                    """
                    [
                      {
                        "number": 5,
                        "title": "Labels test",
                        "state": "open",
                        "html_url": "https://github.com/o/r/issues/5",
                        "body": "",
                        "labels": [{"name": "bug"}, {"name": "P0"}, {"name": "phase-1"}]
                      }
                    ]
                    """;

            List<GitHubIssueData> result = parseJson(json);

            assertThat(result.get(0).labels()).containsExactly("bug", "P0", "phase-1");
        }
    }

    @Nested
    class Pagination {

        @Test
        void singlePageReturnsAllIssues() {
            var client = stubbedClient(List.of(new IssuePage(List.of(issue(1), issue(2)), 2)));

            List<GitHubIssueData> result = client.fetchAllIssues("o", "r");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).number()).isEqualTo(1);
            assertThat(result.get(1).number()).isEqualTo(2);
        }

        @Test
        void paginatesUntilPartialPage() {
            var client = stubbedClient(
                    List.of(new IssuePage(makeIssues(1, 100), 100), new IssuePage(makeIssues(101, 130), 30)));

            List<GitHubIssueData> result = client.fetchAllIssues("o", "r");

            assertThat(result).hasSize(130);
            assertThat(result.get(0).number()).isEqualTo(1);
            assertThat(result.get(129).number()).isEqualTo(130);
        }

        @Test
        void paginatesCorrectlyWhenPRsFilteredFromFullPage() {
            // 100 raw items but only 90 issues (10 PRs filtered) — should still fetch next page
            var client =
                    stubbedClient(List.of(new IssuePage(makeIssues(1, 90), 100), new IssuePage(makeIssues(91, 95), 5)));

            List<GitHubIssueData> result = client.fetchAllIssues("o", "r");

            assertThat(result).hasSize(95);
        }

        @Test
        void emptyPageReturnsEmpty() {
            var client = stubbedClient(List.of(new IssuePage(List.of(), 0)));

            List<GitHubIssueData> result = client.fetchAllIssues("o", "r");

            assertThat(result).isEmpty();
        }

        private GitHubCliClient stubbedClient(List<IssuePage> pages) {
            return new GitHubCliClient(new ObjectMapper(), "gh") {
                private int callCount = 0;

                @Override
                protected IssuePage fetchIssuePage(String owner, String repo, int page) {
                    return pages.get(callCount++);
                }
            };
        }

        private List<GitHubIssueData> makeIssues(int from, int to) {
            List<GitHubIssueData> issues = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                issues.add(issue(i));
            }
            return issues;
        }

        private GitHubIssueData issue(int number) {
            return new GitHubIssueData(
                    number, "Issue " + number, "OPEN", "https://github.com/o/r/issues/" + number, "", List.of());
        }
    }

    @Nested
    class CreateIssueUrlParsing {

        @Test
        void parsesIssueNumberFromUrl() {
            String url = "https://github.com/KeplerOps/Ground-Control/issues/42";
            Pattern pattern = Pattern.compile("/issues/(\\d+)$");
            Matcher matcher = pattern.matcher(url);
            assertThat(matcher.find()).isTrue();
            assertThat(Integer.parseInt(matcher.group(1))).isEqualTo(42);
        }
    }

    @Nested
    class InputValidation {

        @ParameterizedTest
        @ValueSource(strings = {"$(whoami)", "foo;rm -rf /", "foo&bar", "foo/bar", "../etc", ""})
        void rejectsMaliciousOwner(String maliciousOwner) {
            assertThatThrownBy(() -> GitHubCliClient.validateOwnerRepo(maliciousOwner, "valid-repo"))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("Invalid GitHub owner");
        }

        @ParameterizedTest
        @ValueSource(strings = {"$(whoami)", "repo;drop table", "foo&bar", "foo/bar", "../..", ""})
        void rejectsMaliciousRepo(String maliciousRepo) {
            assertThatThrownBy(() -> GitHubCliClient.validateOwnerRepo("valid-owner", maliciousRepo))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("Invalid GitHub repo");
        }

        @Test
        void rejectsNullOwner() {
            assertThatThrownBy(() -> GitHubCliClient.validateOwnerRepo(null, "repo"))
                    .isInstanceOf(GroundControlException.class);
        }

        @Test
        void rejectsNullRepo() {
            assertThatThrownBy(() -> GitHubCliClient.validateOwnerRepo("owner", null))
                    .isInstanceOf(GroundControlException.class);
        }

        @Test
        void acceptsValidOwnerRepo() {
            GitHubCliClient.validateOwnerRepo("KeplerOps", "Ground-Control");
            GitHubCliClient.validateOwnerRepo("user123", "my.repo_v2");
        }

        @Test
        void rejectsInvalidRepoSlug() {
            assertThatThrownBy(() -> GitHubCliClient.validateRepoSlug("not-a-slug"))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("owner/repo");
        }

        @Test
        void fetchPullRequestRejectsNonPositiveNumberBeforeAnyGhCall() {
            var client = new GitHubCliClient(new ObjectMapper(), "gh");
            assertThatThrownBy(() -> client.fetchPullRequest("acme", "repo", 0))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void acceptsValidRepoSlug() {
            GitHubCliClient.validateRepoSlug("KeplerOps/Ground-Control");
        }

        @Test
        void rejectsNullTitle() {
            List<String> empty = List.of();
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent(null, "body", empty))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsBlankTitle() {
            List<String> empty = List.of();
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent("", "body", empty))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsTooLongTitle() {
            String longTitle = "x".repeat(GitHubCliClient.MAX_TITLE_LENGTH + 1);
            List<String> empty = List.of();
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent(longTitle, "body", empty))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsTooLongBody() {
            String longBody = "x".repeat(GitHubCliClient.MAX_BODY_LENGTH + 1);
            List<String> empty = List.of();
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent("title", longBody, empty))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("body");
        }

        @Test
        void rejectsInvalidLabel() {
            List<String> labels = List.of("valid", "bad;label");
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent("title", "body", labels))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("label");
        }

        @Test
        void rejectsTooLongLabel() {
            String longLabel = "x".repeat(GitHubCliClient.MAX_LABEL_LENGTH + 1);
            List<String> labels = List.of(longLabel);
            assertThatThrownBy(() -> GitHubCliClient.validateIssueContent("title", "body", labels))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("label");
        }

        @Test
        void acceptsValidIssueContent() {
            GitHubCliClient.validateIssueContent("Valid Title", "Valid body", List.of("bug", "P0"));
        }

        @Test
        void acceptsNullBody() {
            GitHubCliClient.validateIssueContent("Valid Title", null, List.of());
        }

        @Test
        void acceptsNullLabels() {
            GitHubCliClient.validateIssueContent("Valid Title", "body", null);
        }
    }

    @Nested
    class ExecGhErrorPaths {

        @Test
        void nonZeroExitCodeThrowsWithStderr() {
            // Use a client pointing to "false" which always exits with code 1
            var client = new GitHubCliClient(new ObjectMapper(), "/usr/bin/false") {
                @Override
                protected IssuePage fetchIssuePage(String owner, String repo, int page) {
                    return super.fetchIssuePage(owner, repo, page);
                }
            };

            assertThatThrownBy(() -> client.fetchAllIssues("test-owner", "test-repo"))
                    .isInstanceOf(GroundControlException.class)
                    .hasMessageContaining("gh CLI exited with code");
        }

        @Test
        void invalidJsonOutputThrows() {
            // Use "echo" to output invalid JSON
            var client = new GitHubCliClient(new ObjectMapper(), "/usr/bin/echo") {
                @Override
                protected IssuePage fetchIssuePage(String owner, String repo, int page) {
                    return super.fetchIssuePage(owner, repo, page);
                }
            };

            assertThatThrownBy(() -> client.fetchAllIssues("test-owner", "test-repo"))
                    .isInstanceOf(GroundControlException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GitHubIssueData> parseJson(String json) throws Exception {
        List<Map<String, Object>> rawIssues = objectMapper.readValue(json, new TypeReference<>() {});
        return GitHubCliClient.parseIssues(rawIssues);
    }
}
