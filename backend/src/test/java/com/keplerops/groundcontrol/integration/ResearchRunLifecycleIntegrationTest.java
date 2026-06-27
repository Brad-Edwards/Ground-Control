package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.CreateProjectCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GC-RSCH-R001/R003/F003/F036/N007/N011 — end-to-end lifecycle against a live
 * Postgres. Validates that the migrations apply, the JPA mappings and Envers
 * audit shadows are consistent, and the four acceptance criteria hold with real
 * persistence (mocked-repo unit tests cannot exercise the schema).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResearchRunLifecycleIntegrationTest extends BaseIntegrationTest {

    private static final String PROJECT = "rsch-run-it";

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ResearchRunService researchRunService;

    @Autowired
    private DataSource dataSource;

    private UUID ensureProject() {
        try {
            return projectService.getByIdentifier(PROJECT).getId();
        } catch (RuntimeException notFound) {
            var intake = new ResearchIntakeCommand(
                    "Lifecycle IT goal",
                    null,
                    com.keplerops.groundcontrol.domain.research.model.ContributionType.REVIEW,
                    com.keplerops.groundcontrol.domain.research.model.IntendedOutput.SCOPING_REVIEW,
                    com.keplerops.groundcontrol.domain.research.model.AutonomyLevel.AUTONOMOUS,
                    List.of(),
                    null,
                    9000L,
                    120,
                    750_000L);
            return projectService
                    .create(new CreateProjectCommand(
                            PROJECT, "Research Run IT", "lifecycle", ProjectType.RESEARCH, intake))
                    .getId();
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM research_run_gate_audit WHERE id IN (SELECT g.id FROM research_run_gate g "
                    + "JOIN research_run r ON g.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate(
                    "DELETE FROM research_run_artifact_audit WHERE id IN (SELECT a.id FROM research_run_artifact a "
                            + "JOIN research_run r ON a.research_run_id = r.id "
                            + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_audit WHERE id IN (SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate(
                    "DELETE FROM research_run_gate WHERE research_run_id IN (SELECT r.id FROM research_run r "
                            + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate(
                    "DELETE FROM research_run_artifact WHERE research_run_id IN (SELECT r.id FROM research_run r "
                            + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run WHERE project_id IN "
                    + "(SELECT id FROM project WHERE identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_intake_audit WHERE id IN (SELECT id FROM research_intake "
                    + "WHERE project_id IN (SELECT id FROM project WHERE identifier = '" + PROJECT + "'))");
            stmt.executeUpdate("DELETE FROM research_intake WHERE project_id IN "
                    + "(SELECT id FROM project WHERE identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM project WHERE identifier = '" + PROJECT + "'");
        }
    }

    @Test
    void fullLifecycle_startGateAdvanceStopResumeSnapshot_withAudit() throws Exception {
        var projectId = ensureProject();

        // AC1: a run records stage, status, owner/actor, timestamps; budgets snapshot from intake.
        // The owner is taken from the authenticated server context (ActorHolder), never the
        // request — clients cannot supply lifecycle provenance (ADR-026).
        ActorHolder.set("it-actor");
        ResearchRun run;
        try {
            run = researchRunService.start(new StartResearchRunCommand(projectId, "RUN-IT-1", null, null, Map.of()));
        } finally {
            ActorHolder.clear();
        }
        assertThat(run.getCurrentStage()).isEqualTo(ResearchRunStage.METHODOLOGY_SELECTION);
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.IN_PROGRESS);
        assertThat(run.getOwnerActor()).isEqualTo("it-actor");
        assertThat(run.getBudgetTokens()).isEqualTo(9000L);
        assertThat(run.getCreatedAt()).isNotNull();
        var runId = run.getId();
        assertThat(researchRunService.listGates(projectId, runId)).hasSize(5);

        // AC2: starting a downstream phase without the required artifact is a validation error.
        var blockedAdvance = new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING);
        assertThatThrownBy(() -> researchRunService.advanceStage(projectId, runId, blockedAdvance))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("required artifact");

        // Record the methodology artifact, then advance (autonomous gate auto-accepts).
        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(
                        ResearchArtifactType.METHODOLOGY_REQUIREMENTS,
                        "ws://m",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        var advanced = researchRunService.advanceStage(
                projectId, runId, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));
        assertThat(advanced.getCurrentStage()).isEqualTo(ResearchRunStage.PROTOCOL_PLANNING);

        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(
                        ResearchArtifactType.PROTOCOL_PLAN, null, null, null, null, null, null, null, null));
        researchRunService.advanceStage(projectId, runId, new AdvanceStageCommand(ResearchRunStage.SOURCE_SEARCH));
        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(ResearchArtifactType.SEARCH_LOG, null, null, "idem-1", 30, 10, 20, 5, 2));

        int artifactsBeforeResume =
                researchRunService.listArtifacts(projectId, runId).size();

        // AC3: a stopped run resumes from the last completed phase without duplicating artifacts.
        researchRunService.stop(projectId, runId);
        assertThat(researchRunService.getById(projectId, runId).getStatus()).isEqualTo(ResearchRunStatus.STOPPED);
        var resumed = researchRunService.resume(projectId, runId);
        assertThat(resumed.getStatus()).isEqualTo(ResearchRunStatus.IN_PROGRESS);
        assertThat(resumed.getCurrentStage()).isEqualTo(ResearchRunStage.SOURCE_SEARCH);
        assertThat(researchRunService.listArtifacts(projectId, runId)).hasSize(artifactsBeforeResume);

        // Idempotent record replay does not duplicate (GC-RSCH-F036).
        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(ResearchArtifactType.SEARCH_LOG, null, null, "idem-1", 30, 10, 20, 5, 2));
        assertThat(researchRunService.listArtifacts(projectId, runId)).hasSize(artifactsBeforeResume);

        // AC4 / N011: state is visible through the read snapshot, composed from persisted state.
        var snapshot = researchRunService.getSnapshot(projectId, runId);
        assertThat(snapshot.currentStage()).isEqualTo(ResearchRunStage.SOURCE_SEARCH);
        assertThat(snapshot.sourceCounts().candidateSources()).isEqualTo(30);
        assertThat(snapshot.sourceCounts().accessGaps()).isEqualTo(2);
        assertThat(snapshot.artifactReadiness())
                .anySatisfy(r -> {
                    assertThat(r.artifactType()).isEqualTo(ResearchArtifactType.SEARCH_LOG);
                    assertThat(r.readiness()).isEqualTo(ResearchArtifactReadiness.READY);
                })
                .anySatisfy(r -> {
                    assertThat(r.artifactType()).isEqualTo(ResearchArtifactType.MANUSCRIPT);
                    assertThat(r.readiness()).isEqualTo(ResearchArtifactReadiness.MISSING);
                });

        // Envers audit shadow captured the run revisions.
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement("SELECT COUNT(*) FROM research_run_audit WHERE id = ?")) {
            ps.setObject(1, runId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    void start_onNonResearchProject_isRejected() {
        var swId = projectService
                .create(new CreateProjectCommand(PROJECT + "-sw", "SW", "x"))
                .getId();
        try {
            var swCommand = new StartResearchRunCommand(swId, "RUN-SW", null, null, Map.of());
            assertThatThrownBy(() -> researchRunService.start(swCommand))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("RESEARCH projects");
        } finally {
            try (var conn = dataSource.getConnection();
                    var stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM project WHERE identifier = '" + PROJECT + "-sw'");
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
