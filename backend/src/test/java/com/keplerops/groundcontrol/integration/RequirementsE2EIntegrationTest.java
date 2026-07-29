package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.GitHubIssueSyncRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubClient;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubIssueData;
import com.keplerops.groundcontrol.domain.requirements.service.GitHubPullRequestData;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end requirements flow against the full Flyway migration set applied by
 * {@link BaseIntegrationTest} — through V209, which adds the ADR-036 durable step-observation columns
 * to {@code workflow_phase_event} and its audit shadow (issue #1354). It exercises the API and the
 * audited persistence spine those migrations build, so a migration that breaks the end-to-end path
 * fails here rather than only in the migration smoke test.
 */
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequirementsE2EIntegrationTest extends BaseIntegrationTest {

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
                                    10,
                                    "Foundation issue",
                                    "CLOSED",
                                    "https://github.com/test/e2e/issues/10",
                                    "Foundation issue body",
                                    List.of("phase-1", "P0")),
                            new GitHubIssueData(
                                    11,
                                    "Relationships issue",
                                    "OPEN",
                                    "https://github.com/test/e2e/issues/11",
                                    "Relationships issue body",
                                    List.of("phase-1", "P1")),
                            new GitHubIssueData(
                                    12,
                                    "Hierarchy issue",
                                    "OPEN",
                                    "https://github.com/test/e2e/issues/12",
                                    "Hierarchy issue body",
                                    List.of("phase-1", "P1")),
                            new GitHubIssueData(
                                    13,
                                    "Multi-level issue",
                                    "OPEN",
                                    "https://github.com/test/e2e/issues/13",
                                    "Multi-level issue body",
                                    List.of("phase-1", "P2")),
                            new GitHubIssueData(
                                    14,
                                    "Integration issue",
                                    "OPEN",
                                    "https://github.com/test/e2e/issues/14",
                                    "Integration issue body",
                                    List.of("phase-2", "P1")));
                }

                @Override
                public List<GitHubPullRequestData> fetchAllPullRequests(String owner, String repo) {
                    return List.of();
                }

                @Override
                public GitHubPullRequestData fetchPullRequest(String owner, String repo, int number) {
                    throw new UnsupportedOperationException("Not used in requirements E2E tests");
                }

                @Override
                public GitHubIssueData createIssue(String repo, String title, String body, List<String> labels) {
                    throw new UnsupportedOperationException("Not used in E2E tests");
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementRelationRepository relationRepository;

    @Autowired
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Autowired
    private GitHubIssueSyncRepository issueSyncRepository;

    @Autowired
    private com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository documentRepository;

    @Autowired
    private com.keplerops.groundcontrol.domain.documents.repository.SectionRepository sectionRepository;

    @Autowired
    private com.keplerops.groundcontrol.domain.documents.repository.SectionContentRepository sectionContentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    // Instance fields shared across ordered steps
    private UUID req001Id;
    private UUID req004Id;
    private UUID crudReqId;

    private UUID defaultProjectId() {
        return projectRepository
                .findByIdentifier("ground-control")
                .orElseThrow()
                .getId();
    }

    @AfterAll
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            // Delete in FK-safe order: section content (references requirements), then
            // audit tables, links/relations, requirements, and finally documents/sections
            stmt.executeUpdate(
                    "DELETE FROM section_content WHERE section_id IN (SELECT id FROM section WHERE document_id IN (SELECT id FROM document WHERE title = 'test-requirements'))");
            stmt.executeUpdate(
                    "DELETE FROM section WHERE document_id IN (SELECT id FROM document WHERE title = 'test-requirements')");
            stmt.executeUpdate("DELETE FROM document WHERE title = 'test-requirements'");
            stmt.executeUpdate(
                    "DELETE FROM traceability_link_audit WHERE requirement_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%')");
            stmt.executeUpdate(
                    "DELETE FROM requirement_relation_audit WHERE source_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%') OR target_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%')");
            stmt.executeUpdate(
                    "DELETE FROM requirement_audit WHERE id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%')");
            stmt.executeUpdate(
                    "DELETE FROM traceability_link WHERE requirement_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%')");
            stmt.executeUpdate(
                    "DELETE FROM requirement_relation WHERE source_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%') OR target_id IN (SELECT id FROM requirement WHERE uid LIKE 'E2E-%')");
            stmt.executeUpdate("DELETE FROM github_issue_sync WHERE issue_number IN (10, 11, 12, 13, 14)");
            stmt.executeUpdate("DELETE FROM requirement WHERE uid LIKE 'E2E-%'");
            stmt.executeUpdate(
                    "DELETE FROM requirement_import WHERE source_file IN ('test-requirements.sdoc', 'test/e2e')");
        }
    }

    @Test
    @Order(1)
    void importStrictdocFixture() throws Exception {
        var sdocContent = new String(
                getClass()
                        .getResourceAsStream("/fixtures/test-requirements.sdoc")
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        var file = new MockMultipartFile(
                "file", "test-requirements.sdoc", "text/plain", sdocContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/admin/import/strictdoc").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importId", notNullValue()))
                .andExpect(jsonPath("$.requirementsParsed", is(5)))
                .andExpect(jsonPath("$.requirementsCreated", is(5)))
                .andExpect(jsonPath("$.relationsCreated", is(2)))
                .andExpect(jsonPath("$.traceabilityLinksCreated", is(5)))
                .andExpect(jsonPath("$.documentsCreated", is(1)))
                .andExpect(jsonPath("$.sectionsCreated", is(2)))
                .andExpect(jsonPath("$.sectionContentsCreated", is(7)));

        // Verify requirements via repositories
        var pid = defaultProjectId();
        assertThat(requirementRepository.findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-001"))
                .isPresent();
        assertThat(requirementRepository.findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-002"))
                .isPresent();
        assertThat(requirementRepository.findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-003"))
                .isPresent();
        assertThat(requirementRepository.findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-004"))
                .isPresent();
        assertThat(requirementRepository.findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-005"))
                .isPresent();

        // Store IDs for subsequent steps
        req001Id = requirementRepository
                .findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-001")
                .get()
                .getId();
        req004Id = requirementRepository
                .findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-004")
                .get()
                .getId();

        // Verify relations created
        var req002 = requirementRepository
                .findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-002")
                .get();
        var req003 = requirementRepository
                .findByProjectIdAndUidIgnoreCase(pid, "E2E-REQ-003")
                .get();
        assertThat(relationRepository.findBySourceId(req002.getId())).hasSize(1);
        assertThat(relationRepository.findBySourceId(req003.getId())).hasSize(1);

        // Verify traceability links created
        assertThat(traceabilityLinkRepository.findByRequirementId(req001Id)).hasSize(1);
        assertThat(traceabilityLinkRepository.findByRequirementId(req003.getId()))
                .hasSize(2);

        // Verify document structure created
        var doc = documentRepository.findByProjectIdAndTitle(pid, "test-requirements");
        assertThat(doc).isPresent();
        var sections =
                sectionRepository.findByDocumentIdOrderBySortOrder(doc.get().getId());
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getTitle()).isEqualTo("Wave 1 — Foundation");
        assertThat(sections.get(1).getTitle()).isEqualTo("Wave 2 — Integration");

        // Verify section content: Wave 1 has 1 text block + 3 requirements = 4 items
        var wave1Content = sectionContentRepository.findBySectionIdOrderBySortOrder(
                sections.get(0).getId());
        assertThat(wave1Content).hasSize(4);

        // Verify section content: Wave 2 has 1 requirement + 1 text block + 1 requirement = 3 items
        var wave2Content = sectionContentRepository.findBySectionIdOrderBySortOrder(
                sections.get(1).getId());
        assertThat(wave2Content).hasSize(3);
    }

    @Test
    @Order(2)
    void syncGitHubIssues() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync/github").param("owner", "test").param("repo", "e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncId", notNullValue()))
                .andExpect(jsonPath("$.issuesFetched", is(5)))
                .andExpect(jsonPath("$.issuesCreated", is(5)));

        // Verify GitHubIssueSync records exist
        assertThat(issueSyncRepository.findByIssueNumber(10)).isPresent();
        assertThat(issueSyncRepository.findByIssueNumber(11)).isPresent();
        assertThat(issueSyncRepository.findByIssueNumber(12)).isPresent();
        assertThat(issueSyncRepository.findByIssueNumber(13)).isPresent();
        assertThat(issueSyncRepository.findByIssueNumber(14)).isPresent();

        // Verify traceability links enriched with synced metadata
        var linksForReq001 = traceabilityLinkRepository.findByRequirementId(req001Id);
        assertThat(linksForReq001).hasSize(1);
        var link = linksForReq001.get(0);
        assertThat(link.getArtifactUrl()).isEqualTo("https://github.com/test/e2e/issues/10");
        assertThat(link.getArtifactTitle()).isEqualTo("#10 - Foundation issue [CLOSED]");
        assertThat(link.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    @Order(3)
    void crudApiWorkflow() throws Exception {
        // Create
        var createBody = Map.of(
                "uid", "E2E-CRUD-001",
                "title", "CRUD test requirement",
                "statement", "Created via E2E CRUD workflow",
                "requirementType", "FUNCTIONAL",
                "priority", "MUST");
        var createResult = mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid", is("E2E-CRUD-001")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andReturn();
        crudReqId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Update title
        var updateBody = Map.of(
                "uid", "E2E-CRUD-001",
                "title", "Updated CRUD title",
                "statement", "Created via E2E CRUD workflow");
        mockMvc.perform(put("/api/v1/requirements/" + crudReqId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated CRUD title")));

        // Create DEPENDS_ON relation to E2E-REQ-004 (not REQ-001, to keep impact analysis deterministic)
        var relationBody = Map.of("targetId", req004Id.toString(), "relationType", "DEPENDS_ON");
        mockMvc.perform(post("/api/v1/requirements/" + crudReqId + "/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(relationBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationType", is("DEPENDS_ON")));

        // Transition DRAFT → ACTIVE (required before creating IMPLEMENTS links)
        mockMvc.perform(post("/api/v1/requirements/" + crudReqId + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        // Create traceability link (requires ACTIVE status for IMPLEMENTS).
        // Assert the response echoes each posted field — without these
        // assertions a silent field swap (wrong link type, mis-deserialized
        // artifact identifier) would still produce a 201 and pass.
        var linkBody = Map.of(
                "artifactType", "GITHUB_ISSUE",
                "artifactIdentifier", "99",
                "linkType", "IMPLEMENTS");
        mockMvc.perform(post("/api/v1/requirements/" + crudReqId + "/traceability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkType", is("IMPLEMENTS")))
                .andExpect(jsonPath("$.artifactType", is("GITHUB_ISSUE")))
                .andExpect(jsonPath("$.artifactIdentifier", is("99")));

        // Archive
        mockMvc.perform(post("/api/v1/requirements/" + crudReqId + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ARCHIVED")))
                .andExpect(jsonPath("$.archivedAt", notNullValue()));
    }

    @Test
    @Order(4)
    void analysisEndpoints() throws Exception {
        // Cycles — acyclic graph → empty
        mockMvc.perform(get("/api/v1/analysis/cycles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Orphans — E2E-REQ-005 has no relations AND no traceability links
        mockMvc.perform(get("/api/v1/analysis/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uid == 'E2E-REQ-005')]").exists());

        // Impact analysis on REQ-001 → 3 results (001, 002, 003 via transitive parent edges)
        mockMvc.perform(get("/api/v1/analysis/impact/" + req001Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // Cross-wave — no violations (all parent relations within wave 1)
        mockMvc.perform(get("/api/v1/analysis/cross-wave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(5)
    @Transactional
    void enversAuditTrail() {
        var auditReader = AuditReaderFactory.get(entityManager);

        // CRUD requirement should have ≥4 revisions (create, update, transition, archive)
        var crudRevisions = auditReader.getRevisions(
                com.keplerops.groundcontrol.domain.requirements.model.Requirement.class, crudReqId);
        assertThat(crudRevisions).hasSizeGreaterThanOrEqualTo(4);

        // Imported requirement should have ≥1 revision
        var importRevisions = auditReader.getRevisions(
                com.keplerops.groundcontrol.domain.requirements.model.Requirement.class, req001Id);
        assertThat(importRevisions).hasSizeGreaterThanOrEqualTo(1);
    }
}
