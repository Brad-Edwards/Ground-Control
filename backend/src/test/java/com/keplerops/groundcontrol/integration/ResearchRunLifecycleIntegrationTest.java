package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.CreateProjectCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.EntryCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.SourceLinkCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.CoverageCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.SectionCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.SelectMethodologyCommand;
import com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand;
import com.keplerops.groundcontrol.domain.research.service.UpdateMethodologySourceStateCommand;
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
            // #1007 ADR-083: protocol plan child rows reference the plan, which
            // references the methodology requirements contract + artifact, so they
            // must be deleted before those parents below.
            stmt.executeUpdate("DELETE FROM protocol_plan_section WHERE protocol_plan_id IN "
                    + "(SELECT pp.id FROM protocol_plan pp "
                    + "JOIN research_run r ON pp.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM protocol_plan_coverage WHERE protocol_plan_id IN "
                    + "(SELECT pp.id FROM protocol_plan pp "
                    + "JOIN research_run r ON pp.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM protocol_plan_audit WHERE id IN "
                    + "(SELECT pp.id FROM protocol_plan pp "
                    + "JOIN research_run r ON pp.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM protocol_plan WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            // #1006 ADR-080: methodology requirements contract child rows reference
            // artifact / methodology-source / rationale / selection / run, so they
            // must be deleted before those parents below.
            stmt.executeUpdate("DELETE FROM methodology_requirements_contract_entry_source_link WHERE entry_id IN "
                    + "(SELECT e.id FROM methodology_requirements_contract_entry e "
                    + "JOIN methodology_requirements_contract c ON e.contract_id = c.id "
                    + "JOIN research_run r ON c.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate(
                    "DELETE FROM methodology_requirements_contract_rejected_alternative WHERE contract_id IN "
                            + "(SELECT c.id FROM methodology_requirements_contract c "
                            + "JOIN research_run r ON c.research_run_id = r.id "
                            + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM methodology_requirements_contract_entry WHERE contract_id IN "
                    + "(SELECT c.id FROM methodology_requirements_contract c "
                    + "JOIN research_run r ON c.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM methodology_requirements_contract_audit WHERE id IN "
                    + "(SELECT c.id FROM methodology_requirements_contract c "
                    + "JOIN research_run r ON c.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM methodology_requirements_contract WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
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
            // #1001 decision surfaces: the new run-scoped child rows (and their audit
            // shadows) must go before the run, or their foreign keys block the
            // research_run delete.
            stmt.executeUpdate("DELETE FROM research_run_disclosure_entry_audit WHERE id IN "
                    + "(SELECT e.id FROM research_run_disclosure_entry e "
                    + "JOIN research_run_disclosure d ON e.disclosure_id = d.id "
                    + "JOIN research_run r ON d.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_disclosure_entry WHERE disclosure_id IN "
                    + "(SELECT d.id FROM research_run_disclosure d "
                    + "JOIN research_run r ON d.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_disclosure_audit WHERE id IN "
                    + "(SELECT d.id FROM research_run_disclosure d "
                    + "JOIN research_run r ON d.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_disclosure WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_gate_decision_log_audit WHERE id IN "
                    + "(SELECT l.id FROM research_run_gate_decision_log l "
                    + "JOIN research_run r ON l.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_gate_decision_log WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_review_comment_audit WHERE id IN "
                    + "(SELECT c.id FROM research_run_review_comment c "
                    + "JOIN research_run r ON c.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_review_comment WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_rationale_entry_audit WHERE id IN "
                    + "(SELECT e.id FROM research_run_rationale_entry e "
                    + "JOIN research_run r ON e.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_rationale_entry WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_methodology_source_audit WHERE id IN "
                    + "(SELECT s.id FROM research_run_methodology_source s "
                    + "JOIN research_run_methodology_selection sel ON s.selection_id = sel.id "
                    + "JOIN research_run r ON sel.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_methodology_source WHERE selection_id IN "
                    + "(SELECT sel.id FROM research_run_methodology_selection sel "
                    + "JOIN research_run r ON sel.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_methodology_selection_audit WHERE id IN "
                    + "(SELECT sel.id FROM research_run_methodology_selection sel "
                    + "JOIN research_run r ON sel.research_run_id = r.id "
                    + "JOIN project p ON r.project_id = p.id WHERE p.identifier = '" + PROJECT + "')");
            stmt.executeUpdate("DELETE FROM research_run_methodology_selection WHERE research_run_id IN "
                    + "(SELECT r.id FROM research_run r "
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

        // GC-RSCH-F006 / ADR-078 — select a real catalog method and read its derived
        // required sources so the METHODOLOGY_REQUIREMENTS coverage gate opens.
        researchRunService.selectMethodology(projectId, runId, new SelectMethodologyCommand("systematic"));
        var sources = researchRunService.listMethodologySources(projectId, runId);
        for (var source : sources) {
            researchRunService.updateMethodologySourceState(
                    projectId,
                    runId,
                    source.getId(),
                    new UpdateMethodologySourceStateCommand(MethodologySourceState.OBTAINED));
            researchRunService.updateMethodologySourceState(
                    projectId,
                    runId,
                    source.getId(),
                    new UpdateMethodologySourceStateCommand(MethodologySourceState.READ));
        }

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

        // GC-RSCH-F007 / ADR-080 — record the structured methodology requirements
        // contract behind that artifact; the protocol plan answers this contract.
        var contractCommand = new RecordMethodologyRequirementsContractCommand(
                List.of(new EntryCommand(
                        ContractEntryKind.REQUIREMENT,
                        "req-1",
                        "the protocol must state databases searched",
                        List.of(new SourceLinkCommand(sources.get(0).getId(), "p.1")),
                        null)),
                List.of());
        researchRunService.recordMethodologyRequirementsContract(projectId, runId, contractCommand);

        var advanced = researchRunService.advanceStage(
                projectId, runId, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));
        assertThat(advanced.getCurrentStage()).isEqualTo(ResearchRunStage.PROTOCOL_PLANNING);

        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(
                        ResearchArtifactType.PROTOCOL_PLAN, null, null, null, null, null, null, null, null));

        // GC-RSCH-F008 / GC-RSCH-F009 / ADR-083 — a complete, non-blocking protocol
        // plan is required before SOURCE_SEARCH may start (the durable search gate).
        researchRunService.recordProtocolPlan(
                projectId,
                runId,
                new RecordProtocolPlanCommand(
                        "1",
                        List.of(new CoverageCommand(
                                "req-1",
                                ProtocolCoverageDisposition.FILLED,
                                "systematic search across the selected databases",
                                ProtocolAnswerProvenance.METHODOLOGY_SOURCE,
                                null,
                                null,
                                null)),
                        systematicReviewSections()));
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

    /**
     * GC-RSCH-F008 / ADR-083 §2 — the SOURCE_SEARCH durable gate: advancing past
     * PROTOCOL_PLANNING is blocked while the active protocol plan carries an
     * unresolved BLOCKING_DECISION_REQUIRED coverage, and allowed once a reworked
     * plan resolves it.
     */
    @Test
    void advanceToSourceSearch_blockedByBlockingCoverage_thenAllowedAfterRework() {
        var projectId = ensureProject();
        ActorHolder.set("it-actor-2");
        ResearchRun run;
        try {
            run = researchRunService.start(new StartResearchRunCommand(projectId, "RUN-IT-2", null, null, Map.of()));
        } finally {
            ActorHolder.clear();
        }
        var runId = run.getId();

        researchRunService.selectMethodology(projectId, runId, new SelectMethodologyCommand("systematic"));
        var sources = researchRunService.listMethodologySources(projectId, runId);
        for (var source : sources) {
            researchRunService.updateMethodologySourceState(
                    projectId,
                    runId,
                    source.getId(),
                    new UpdateMethodologySourceStateCommand(MethodologySourceState.OBTAINED));
            researchRunService.updateMethodologySourceState(
                    projectId,
                    runId,
                    source.getId(),
                    new UpdateMethodologySourceStateCommand(MethodologySourceState.READ));
        }
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
        researchRunService.recordMethodologyRequirementsContract(
                projectId,
                runId,
                new RecordMethodologyRequirementsContractCommand(
                        List.of(new EntryCommand(
                                ContractEntryKind.OPEN_PROTOCOL_QUESTION,
                                "oq-1",
                                "which risk-of-bias tool?",
                                List.of(new SourceLinkCommand(sources.get(0).getId(), "p.1")),
                                null)),
                        List.of()));
        researchRunService.advanceStage(projectId, runId, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));

        // First PROTOCOL_PLAN attempt leaves oq-1 as BLOCKING_DECISION_REQUIRED.
        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(
                        ResearchArtifactType.PROTOCOL_PLAN, null, null, null, null, null, null, null, null));
        researchRunService.recordProtocolPlan(
                projectId,
                runId,
                new RecordProtocolPlanCommand(
                        "1",
                        List.of(new CoverageCommand(
                                "oq-1",
                                ProtocolCoverageDisposition.BLOCKING_DECISION_REQUIRED,
                                null,
                                null,
                                "needs a human call on the RoB tool",
                                null,
                                null)),
                        systematicReviewSections()));
        var blockedAdvance = new AdvanceStageCommand(ResearchRunStage.SOURCE_SEARCH);
        assertThatThrownBy(() -> researchRunService.advanceStage(projectId, runId, blockedAdvance))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_blocking");
        assertThat(researchRunService.getById(projectId, runId).getCurrentStage())
                .isEqualTo(ResearchRunStage.PROTOCOL_PLANNING);

        // Rework the artifact (new attempt) with a plan that resolves oq-1: now allowed.
        researchRunService.recordArtifact(
                projectId,
                runId,
                new RecordArtifactCommand(
                        ResearchArtifactType.PROTOCOL_PLAN, null, null, null, null, null, null, null, null));
        researchRunService.recordProtocolPlan(
                projectId,
                runId,
                new RecordProtocolPlanCommand(
                        "1",
                        List.of(new CoverageCommand(
                                "oq-1",
                                ProtocolCoverageDisposition.RESOLVED_BY_USER_DECISION,
                                null,
                                null,
                                null,
                                null,
                                "gate-decision:PROTOCOL_DECISION")),
                        systematicReviewSections()));
        var advanced = researchRunService.advanceStage(
                projectId, runId, new AdvanceStageCommand(ResearchRunStage.SOURCE_SEARCH));
        assertThat(advanced.getCurrentStage()).isEqualTo(ResearchRunStage.SOURCE_SEARCH);
    }

    private static List<SectionCommand> systematicReviewSections() {
        return List.of(
                new SectionCommand(
                        "s-eligibility", ProtocolSectionKind.ELIGIBILITY_CRITERIA, null, "eligibility criteria"),
                new SectionCommand(
                        "s-databases",
                        ProtocolSectionKind.DATABASES_SEARCH_STRINGS,
                        null,
                        "databases and search strings"),
                new SectionCommand("s-screening", ProtocolSectionKind.SCREENING, null, "screening process"),
                new SectionCommand("s-extraction", ProtocolSectionKind.DATA_EXTRACTION, null, "data extraction plan"),
                new SectionCommand("s-rob", ProtocolSectionKind.RISK_OF_BIAS_POSTURE, null, "risk of bias posture"),
                new SectionCommand("s-synthesis", ProtocolSectionKind.SYNTHESIS_PLAN, null, "synthesis plan"),
                new SectionCommand("s-reporting", ProtocolSectionKind.REPORTING_STANDARD, null, "reporting standard"),
                new SectionCommand(
                        "s-certainty", ProtocolSectionKind.CERTAINTY_CLAIM_LIMITS, null, "certainty and claim limits"),
                new SectionCommand("s-limits", ProtocolSectionKind.METHOD_LIMITS, null, "method limits"),
                new SectionCommand("s-nonclaims", ProtocolSectionKind.NON_CLAIMS, null, "non-claims"));
    }
}
