package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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
class CreateIssueIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration
    static class MockGitHubClientConfig {

        @Bean
        @Primary
        GitHubClient mockGitHubClient() {
            return new GitHubClient() {
                @Override
                public List<GitHubIssueData> fetchAllIssues(String owner, String repo) {
                    return List.of(new GitHubIssueData(
                            55,
                            "CI-REQ-001: Test req",
                            "OPEN",
                            "https://github.com/test/repo/issues/55",
                            "Created from requirement CI-REQ-001",
                            List.of("phase-1", "P1")));
                }

                @Override
                public List<GitHubPullRequestData> fetchAllPullRequests(String owner, String repo) {
                    return List.of();
                }

                @Override
                public GitHubPullRequestData fetchPullRequest(String owner, String repo, int number) {
                    throw new UnsupportedOperationException("Not used in create-issue tests");
                }

                @Override
                public GitHubIssueData createIssue(String repo, String title, String body, List<String> labels) {
                    return new GitHubIssueData(
                            55, title, "OPEN", "https://github.com/test/repo/issues/55", body, labels);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Project testProject;
    private Requirement testRequirement;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
        var req = new Requirement(testProject, "CI-REQ-001", "Test req", "Test statement");
        req.transitionStatus(com.keplerops.groundcontrol.domain.requirements.state.Status.ACTIVE);
        testRequirement = requirementRepository.save(req);
    }

    @Test
    void createIssueFromRequirement_storesRawIntegerIdentifier() throws Exception {
        mockMvc.perform(post("/api/v1/admin/github/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requirementUid", "CI-REQ-001", "repo", "test/repo", "labels", List.of())))
                        .param("project", "ground-control"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueNumber", Matchers.is(55)))
                .andExpect(jsonPath("$.traceabilityLinkId", Matchers.notNullValue()));

        var links = traceabilityLinkRepository.findByRequirementId(testRequirement.getId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getArtifactType()).isEqualTo(ArtifactType.GITHUB_ISSUE);
        assertThat(links.get(0).getArtifactIdentifier()).isEqualTo("55");
        assertThat(links.get(0).getArtifactIdentifier()).doesNotStartWith("#");
    }

    @Test
    void createIssueFromRequirement_thenSync_updatesTraceabilityLink() throws Exception {
        // Step 1: Create issue from requirement — this stores the traceability link
        mockMvc.perform(post("/api/v1/admin/github/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requirementUid", "CI-REQ-001", "repo", "test/repo", "labels", List.of())))
                        .param("project", "ground-control"))
                .andExpect(status().isCreated());

        // Step 2: Sync GitHub issues — should update the traceability link using the same raw integer format
        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linksUpdated", Matchers.is(1)));

        var links = traceabilityLinkRepository.findByRequirementId(testRequirement.getId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getArtifactUrl()).isEqualTo("https://github.com/test/repo/issues/55");
        assertThat(links.get(0).getArtifactTitle()).isEqualTo("#55 - CI-REQ-001: Test req [OPEN]");
        assertThat(links.get(0).getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }
}
