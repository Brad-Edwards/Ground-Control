package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GraphClient;
import com.keplerops.groundcontrol.domain.requirements.service.ImportService;
import com.keplerops.groundcontrol.infrastructure.age.AgeGraphSnapshotRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

// Intentionally NOT @Transactional: this is an ordered accumulate-then-verify sequence — import
// (Order 1) → materialize (Order 2) → ancestor/descendant queries (Order 3/4). Each @Test method
// runs in its own transaction, so a class-level @Transactional would roll the import back before
// materialization could read it, and (under ADR-062) roll the published snapshot back before the
// read methods could resolve its active pointer. Committing between methods is what makes the
// cross-method verification meaningful; the Testcontainers database is torn down with the JVM.
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequirementsE2EAgeIntegrationTest extends BaseAgeIntegrationTest {

    @Autowired
    private ImportService importService;

    @Autowired
    private GraphClient graphClient;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgeGraphSnapshotRepository snapshotRepository;

    private Project testProject;

    @BeforeAll
    void setUpProject() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    @Test
    @Order(1)
    void importFixtureForGraph() throws Exception {
        var sdocContent = new String(
                getClass()
                        .getResourceAsStream("/fixtures/test-requirements.sdoc")
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        var result = importService.importStrictdoc(testProject.getId(), "test-requirements.sdoc", sdocContent);
        assertThat(result.requirementsCreated()).isEqualTo(5);
        assertThat(result.relationsCreated()).isEqualTo(2);
    }

    @Test
    @Order(2)
    void materializeGraph() {
        graphClient.materializeGraph();
        // Gate the setup step itself: a no-op or all-swallowing materialize would otherwise pass
        // silently here and surface only as empty ancestor/descendant results in Order(3)/(4),
        // misdirecting triage to the read path rather than to publication.
        assertThat(snapshotRepository.findActiveGraphName())
                .as("a snapshot must be published after materialization")
                .isPresent();
    }

    @Test
    @Order(3)
    void ancestorQueryMatchesJPA() {
        var ancestors = graphClient.getAncestors(testProject.getId(), "E2E-REQ-003", 10);
        assertThat(ancestors).contains("E2E-REQ-002", "E2E-REQ-001");
    }

    @Test
    @Order(4)
    void descendantQueryMatchesJPA() {
        var descendants = graphClient.getDescendants(testProject.getId(), "E2E-REQ-001", 10);
        assertThat(descendants).contains("E2E-REQ-002", "E2E-REQ-003");
    }
}
