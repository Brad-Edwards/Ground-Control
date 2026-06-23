package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the project-scoped reverse artifact lookup (#1052).
 *
 * <p>Two projects each have a GITHUB_ISSUE link with the same artifact identifier ("61").
 * The project-scoped query must return only the queried project's link.
 */
@Transactional
class ProjectScopedTraceabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TraceabilityService traceabilityService;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManager entityManager;

    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        projectA = projectRepository.findByIdentifier("ground-control").orElseThrow();
        // Create a second project to test cross-project isolation
        projectB = new Project("test-project-b-" + System.nanoTime(), "Test Project B");
        projectRepository.save(projectB);
        entityManager.flush();
    }

    @Test
    void scopedQueryReturnsOnlyQueriedProjectsLink() {
        // Both projects have a GITHUB_ISSUE link for issue "61"
        var reqA = new Requirement(projectA, "SCRTA-001", "Req in Project A", "Statement A");
        requirementRepository.save(reqA);
        var linkA = new TraceabilityLink(reqA, ArtifactType.GITHUB_ISSUE, "61", LinkType.IMPLEMENTS);
        traceabilityLinkRepository.save(linkA);

        var reqB = new Requirement(projectB, "SCRTB-001", "Req in Project B", "Statement B");
        requirementRepository.save(reqB);
        var linkB = new TraceabilityLink(reqB, ArtifactType.GITHUB_ISSUE, "61", LinkType.IMPLEMENTS);
        traceabilityLinkRepository.save(linkB);

        entityManager.flush();
        entityManager.clear();

        // Project A scope: only A's link
        var linksForA = traceabilityService.findByArtifact(ArtifactType.GITHUB_ISSUE, "61", projectA.getId());
        assertThat(linksForA).hasSize(1);
        assertThat(linksForA.get(0).getRequirement().getProject().getId()).isEqualTo(projectA.getId());

        // Project B scope: only B's link
        var linksForB = traceabilityService.findByArtifact(ArtifactType.GITHUB_ISSUE, "61", projectB.getId());
        assertThat(linksForB).hasSize(1);
        assertThat(linksForB.get(0).getRequirement().getProject().getId()).isEqualTo(projectB.getId());
    }

    @Test
    void nullProjectReturnsAllProjects() {
        // With null project, the unscoped query returns links from both projects
        var reqA = new Requirement(projectA, "SCRTNA-001", "Req in Project A", "Statement A");
        requirementRepository.save(reqA);
        var linkA = new TraceabilityLink(reqA, ArtifactType.GITHUB_ISSUE, "99", LinkType.IMPLEMENTS);
        traceabilityLinkRepository.save(linkA);

        var reqB = new Requirement(projectB, "SCRTNB-001", "Req in Project B", "Statement B");
        requirementRepository.save(reqB);
        var linkB = new TraceabilityLink(reqB, ArtifactType.GITHUB_ISSUE, "99", LinkType.IMPLEMENTS);
        traceabilityLinkRepository.save(linkB);

        entityManager.flush();
        entityManager.clear();

        // null projectId → unscoped → both links returned
        var allLinks = traceabilityService.findByArtifact(ArtifactType.GITHUB_ISSUE, "99", null);
        assertThat(allLinks).hasSize(2);
    }
}
