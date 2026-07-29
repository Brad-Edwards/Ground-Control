package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubIssueSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class SyncIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration
    static class MockGitHubClientConfig {

        @Bean
        @Primary
        GitHubClient mockGitHubClient() {
            return new GitHubClient() {
                @Override
                public List<GitHubIssueData> fetchAllIssues(String owner, String repo) {
                    return List.of(
                            new GitHubIssueData(
                                    1,
                                    "Setup CI",
                                    "CLOSED",
                                    "https://github.com/test/repo/issues/1",
                                    "Initial CI setup. See #2",
                                    List.of("phase-0", "P0")),
                            new GitHubIssueData(
                                    2,
                                    "Add linting",
                                    "OPEN",
                                    "https://github.com/test/repo/issues/2",
                                    "Add linting tools",
                                    List.of("phase-0", "P1", "enhancement")));
                }

                @Override
                public List<GitHubPullRequestData> fetchAllPullRequests(String owner, String repo) {
                    return List.of();
                }

                @Override
                public GitHubPullRequestData fetchPullRequest(String owner, String repo, int number) {
                    throw new UnsupportedOperationException("Not used in sync tests");
                }

                @Override
                public GitHubIssueData createIssue(String repo, String title, String body, List<String> labels) {
                    throw new UnsupportedOperationException("Not used in sync tests");
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitHubIssueSyncRepository issueSyncRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    @Test
    void syncGithubIssues_createsIssueSyncRecords() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncId", notNullValue()))
                .andExpect(jsonPath("$.issuesFetched", is(2)))
                .andExpect(jsonPath("$.issuesCreated", is(2)))
                .andExpect(jsonPath("$.issuesUpdated", is(0)));

        var sync1 = issueSyncRepository.findByIssueNumber(1);
        // AssertJ instead of Java assert so the checks run without -ea
        // (codex pre-push cycle 3 class finding F3).
        assertThat(sync1).isPresent();
        assertThat(sync1.get().getIssueTitle()).isEqualTo("Setup CI");
        assertThat(sync1.get().getPhase()).isZero();
        assertThat(sync1.get().getPriorityLabel()).isEqualTo("P0");
        assertThat(sync1.get().getCrossReferences()).contains(2);
    }

    @Test
    void syncGithubIssues_isIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuesCreated", is(2)));

        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuesCreated", is(0)))
                .andExpect(jsonPath("$.issuesUpdated", is(2)));
    }

    @Test
    void syncGithubIssues_updatesTraceabilityLinks() throws Exception {
        // Pre-create a requirement with a traceability link to issue #1
        var requirement = new Requirement(testProject, "SYNC-REQ-001", "Test requirement", "Statement");
        requirement = requirementRepository.save(requirement);

        var link = new TraceabilityLink(requirement, ArtifactType.GITHUB_ISSUE, "1", LinkType.IMPLEMENTS);
        traceabilityLinkRepository.save(link);

        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linksUpdated", is(1)));

        var updatedLinks = traceabilityLinkRepository.findByRequirementId(requirement.getId());
        // AssertJ instead of Java assert so the checks run without -ea
        // (codex pre-push cycle 3 class finding F3).
        assertThat(updatedLinks).hasSize(1);
        assertThat(updatedLinks.get(0).getArtifactUrl()).isEqualTo("https://github.com/test/repo/issues/1");
        assertThat(updatedLinks.get(0).getArtifactTitle()).isEqualTo("#1 - Setup CI [CLOSED]");
    }

    @Test
    void mcpTraceabilityRequest_thenSync_updatesThePersistedLink() throws Exception {
        var requirement = new Requirement(testProject, "SYNC-MCP-001", "MCP-created issue", "Statement");
        requirement.transitionStatus(Status.ACTIVE);
        requirement = requirementRepository.save(requirement);

        // This is the exact REST shape emitted by createGitHubIssueFromRequirement; its Node
        // contract test independently asserts that issue 431 is serialized as "431", not "#431".
        var mcpRequest = Map.of(
                "artifactType", "GITHUB_ISSUE",
                "artifactIdentifier", "1",
                "artifactUrl", "https://github.com/test/repo/issues/1",
                "artifactTitle", "MCP-created issue",
                "linkType", "IMPLEMENTS");
        mockMvc.perform(post("/api/v1/requirements/" + requirement.getId() + "/traceability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mcpRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.artifactIdentifier", is("1")));

        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linksUpdated", is(1)));

        var links = traceabilityLinkRepository.findByRequirementId(requirement.getId());
        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.getArtifactIdentifier()).isEqualTo("1");
            assertThat(link.getArtifactUrl()).isEqualTo("https://github.com/test/repo/issues/1");
            assertThat(link.getArtifactTitle()).isEqualTo("#1 - Setup CI [CLOSED]");
        });
    }
}
