package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * GC-RSCH-R001/R003/F003/F036/N007/N011 — behavioral unit tests for {@link
 * ResearchRunService}. Each test exercises a real lifecycle behavior (gate
 * policy, prerequisite gating, idempotent record, resume-without-duplication,
 * snapshot composition) rather than asserting a tautology.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchRunArtifactRepository artifactRepository;

    @Mock
    private ResearchRunGateRepository gateRepository;

    @Mock
    private ResearchIntakeRepository intakeRepository;

    @Mock
    private ProjectService projectService;

    private ResearchRunService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new ResearchRunService(
                runRepository, artifactRepository, gateRepository, intakeRepository, projectService);
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(runRepository.save(any())).thenAnswer(inv -> {
            ResearchRun r = inv.getArgument(0);
            if (r.getId() == null) {
                TestUtil.setField(r, "id", RUN_ID);
            }
            return r;
        });
        when(gateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.save(any())).thenAnswer(inv -> {
            ResearchRunArtifact a = inv.getArgument(0);
            if (a.getId() == null) {
                TestUtil.setField(a, "id", UUID.randomUUID());
            }
            return a;
        });
    }

    private ResearchRun runAt(ResearchRunStage stage, ResearchRunStatus status, AutonomyLevel autonomy) {
        var run = new ResearchRun(project, "RUN-1", autonomy);
        TestUtil.setField(run, "id", RUN_ID);
        TestUtil.setField(run, "currentStage", stage);
        TestUtil.setField(run, "status", status);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private ResearchRunArtifact artifact(ResearchRun run, ResearchArtifactType type, ResearchArtifactStatus status) {
        var a = new ResearchRunArtifact(run, type, 1);
        TestUtil.setField(a, "id", UUID.randomUUID());
        TestUtil.setField(a, "status", status);
        return a;
    }

    private ResearchRunGate gate(ResearchRun run, ResearchGatePoint point, ResearchGateBehavior behavior) {
        return new ResearchRunGate(run, point, behavior, "test");
    }

    // ---------------------------------------------------------------- start

    @Test
    void start_snapshotsIntakeAndCopilotGatesRequireHuman() {
        var intake = new com.keplerops.groundcontrol.domain.research.model.ResearchIntake(
                project,
                "goal",
                com.keplerops.groundcontrol.domain.research.model.ContributionType.REVIEW,
                IntendedOutput.SCOPING_REVIEW,
                AutonomyLevel.COPILOT,
                List.of());
        intake.setBudgetTokens(1000L);
        when(intakeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(intake));
        when(runRepository.existsByProjectIdAndUid(PROJECT_ID, "RUN-1")).thenReturn(false);

        var run = service.start(new StartCmd("RUN-1", null, null).toCommand());

        assertThat(run.getCurrentStage()).isEqualTo(ResearchRunStage.METHODOLOGY_SELECTION);
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.IN_PROGRESS);
        assertThat(run.getAutonomyLevel()).isEqualTo(AutonomyLevel.COPILOT);
        assertThat(run.getIntendedOutput()).isEqualTo(IntendedOutput.SCOPING_REVIEW);
        assertThat(run.getBudgetTokens()).isEqualTo(1000L);

        var captor = ArgumentCaptor.forClass(ResearchRunGate.class);
        verify(gateRepository, org.mockito.Mockito.times(5)).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(5).allSatisfy(g -> assertThat(g.getBehavior())
                .isEqualTo(ResearchGateBehavior.REQUIRE_HUMAN));
    }

    @Test
    void start_autonomousMode_gatesUseAutonomousDefault() {
        when(intakeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(runRepository.existsByProjectIdAndUid(PROJECT_ID, "RUN-1")).thenReturn(false);

        service.start(new StartCmd("RUN-1", AutonomyLevel.AUTONOMOUS, null).toCommand());

        var captor = ArgumentCaptor.forClass(ResearchRunGate.class);
        verify(gateRepository, org.mockito.Mockito.times(5)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(g -> assertThat(g.getBehavior()).isEqualTo(ResearchGateBehavior.AUTONOMOUS_DEFAULT));
    }

    @Test
    void start_duplicateUid_throwsConflict() {
        when(intakeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(runRepository.existsByProjectIdAndUid(PROJECT_ID, "RUN-1")).thenReturn(true);
        var command = new StartCmd("RUN-1", AutonomyLevel.COPILOT, null).toCommand();
        assertThatThrownBy(() -> service.start(command)).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_noAutonomyAndNoIntake_throwsValidation() {
        when(intakeRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(runRepository.existsByProjectIdAndUid(PROJECT_ID, "RUN-1")).thenReturn(false);
        var command = new StartCmd("RUN-1", null, null).toCommand();
        assertThatThrownBy(() -> service.start(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("autonomyLevel is required");
    }

    // ------------------------------------------------------------- artifacts

    @Test
    void recordArtifact_wrongTypeForCurrentStage_throwsValidation() {
        runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var cmd = new com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand(
                ResearchArtifactType.PROTOCOL_PLAN, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordArtifact(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("does not match current stage");
    }

    @Test
    void recordArtifact_idempotentReplay_returnsExistingWithoutDuplicating() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var existing = artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndIdempotencyKey(RUN_ID, "key-1"))
                .thenReturn(Optional.of(existing));
        var cmd = new com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, "key-1", null, null, null, null, null);

        var result = service.recordArtifact(PROJECT_ID, RUN_ID, cmd);

        assertThat(result).isSameAs(existing);
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void recordArtifact_rework_supersedesPriorActiveRecord() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var prior = artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(prior));
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.empty());
        var cmd = new com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, "loc", null, null, null, null, null, null, null);

        var saved = service.recordArtifact(PROJECT_ID, RUN_ID, cmd);

        assertThat(saved.getAttemptNo()).isEqualTo(2);
        assertThat(prior.getStatus()).isEqualTo(ResearchArtifactStatus.SUPERSEDED);
        assertThat(prior.getSupersededByArtifactId()).isEqualTo(saved.getId());
    }

    @Test
    void recordArtifact_appliesBoundedSourceCounts() {
        runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var cmd = new com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand(
                ResearchArtifactType.SEARCH_LOG, null, null, null, 42, null, null, null, 3);
        service.recordArtifact(PROJECT_ID, RUN_ID, cmd);
        var captor = ArgumentCaptor.forClass(ResearchRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getCandidateSources()).isEqualTo(42);
        assertThat(captor.getValue().getAccessGaps()).isEqualTo(3);
    }

    // --------------------------------------------------------------- advance

    @Test
    void advanceStage_missingRequiredArtifact_throwsStageBlocked() {
        runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.empty());
        var command = new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING);
        assertThatThrownBy(() -> service.advanceStage(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("required artifact");
    }

    @Test
    void advanceStage_pendingRequiredHumanGate_throwsConflict() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(
                        artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)));
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(
                        Optional.of(gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN)));
        var command = new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING);
        assertThatThrownBy(() -> service.advanceStage(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("must be resolved");
    }

    @Test
    void advanceStage_autonomousGate_autoAcceptsAndAdvances() {
        var run =
                runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(
                        artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)));
        var g = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.AUTONOMOUS_DEFAULT);
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(g));

        var advanced =
                service.advanceStage(PROJECT_ID, RUN_ID, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));

        assertThat(advanced.getCurrentStage()).isEqualTo(ResearchRunStage.PROTOCOL_PLANNING);
        assertThat(g.getStatus()).isEqualTo(ResearchGateStatus.RESOLVED);
        assertThat(g.getDecisionOutcome()).isEqualTo(ResearchGateDecisionOutcome.AUTO_ACCEPTED);
    }

    @Test
    void advanceStage_nonSequentialTarget_throwsValidation() {
        runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var command = new AdvanceStageCommand(ResearchRunStage.SCREENING);
        assertThatThrownBy(() -> service.advanceStage(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not the next stage");
    }

    @Test
    void advanceStage_alreadyAtOrPastTarget_isIdempotentNoop() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var result =
                service.advanceStage(PROJECT_ID, RUN_ID, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));
        assertThat(result.getCurrentStage()).isEqualTo(ResearchRunStage.SYNTHESIS);
        verify(artifactRepository, never()).findByResearchRunIdAndArtifactTypeAndStatus(any(), any(), any());
    }

    // ----------------------------------------------------------------- gates

    @Test
    void resolveGate_rejected_blocksRun_andResolvedGateIsImmutable() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var g = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(g));

        service.resolveGate(
                PROJECT_ID,
                RUN_ID,
                new com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand(
                        ResearchGatePoint.METHOD_DECISION, ResearchGateDecisionOutcome.REJECTED, null, "needs work"));
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.BLOCKED);
        assertThat(g.getDecisionOutcome()).isEqualTo(ResearchGateDecisionOutcome.REJECTED);

        // A resolved gate cannot be re-decided in place: overriding a rejection
        // without artifact rework would defeat the gate. The caller must rework
        // the guarded artifact (which reopens the gate) to decide again.
        var command = new com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand(
                ResearchGatePoint.METHOD_DECISION, ResearchGateDecisionOutcome.APPROVED, "opt-1", "ok");
        assertThatThrownBy(() -> service.resolveGate(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already resolved");
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.BLOCKED);
    }

    @Test
    void recordArtifact_rework_reopensResolvedGuardingGateAndUnblocksRun() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.BLOCKED, AutonomyLevel.COPILOT);
        var prior = artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(prior));
        var rejectedGate = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        rejectedGate.resolve(ResearchGateDecisionOutcome.REJECTED, null, "needs work", "server-actor");
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(rejectedGate));

        service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand(
                        ResearchArtifactType.METHODOLOGY_REQUIREMENTS,
                        "loc-v2",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThat(rejectedGate.getStatus())
                .isEqualTo(com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus.PENDING);
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.IN_PROGRESS);
    }

    @Test
    void resolveGate_disabledGate_throwsValidation() {
        var run =
                runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.DISABLED)));
        var command = new com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand(
                ResearchGatePoint.METHOD_DECISION, ResearchGateDecisionOutcome.APPROVED, null, null);
        assertThatThrownBy(() -> service.resolveGate(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("disabled");
    }

    // --------------------------------------------------- stop / fail / resume

    @Test
    void fail_recordsBoundedErrorAndTransitions() {
        var run = runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        service.fail(
                PROJECT_ID,
                RUN_ID,
                new com.keplerops.groundcontrol.domain.research.service.FailRunCommand(
                        "provider_timeout", "RETRYABLE", "upstream timed out"));
        assertThat(run.getStatus()).isEqualTo(ResearchRunStatus.FAILED);
        assertThat(run.getLastErrorCode()).isEqualTo("provider_timeout");
        assertThat(run.getLastErrorClass()).isEqualTo("RETRYABLE");
    }

    @Test
    void resume_fromFailed_returnsToInProgressWithoutTouchingArtifacts() {
        runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.FAILED, AutonomyLevel.AUTONOMOUS);
        var resumed = service.resume(PROJECT_ID, RUN_ID);
        assertThat(resumed.getStatus()).isEqualTo(ResearchRunStatus.IN_PROGRESS);
        assertThat(resumed.getCurrentStage()).isEqualTo(ResearchRunStage.SOURCE_SEARCH);
        // AC3: completed work is never duplicated — resume creates/saves no artifacts.
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void resume_notResumable_throwsValidation() {
        runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        assertThatThrownBy(() -> service.resume(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not resumable");
    }

    @Test
    void recordUsage_accumulatesObservedUsage() {
        var run = runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        service.recordUsage(PROJECT_ID, RUN_ID, 100, 250);
        service.recordUsage(PROJECT_ID, RUN_ID, 50, 125);
        assertThat(run.getObservedTokens()).isEqualTo(150);
        assertThat(run.getObservedCostUsdMicros()).isEqualTo(375);
    }

    // -------------------------------------------------------------- snapshot

    @Test
    void getSnapshot_composesReadinessAndPendingGatesFromState() {
        var run = runAt(ResearchRunStage.PROTOCOL_PLANNING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        run.setCandidateSources(10);
        var methodologyArtifact =
                artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdOrderByCreatedAtAsc(RUN_ID))
                .thenReturn(List.of(methodologyArtifact));
        when(gateRepository.findByResearchRunIdOrderByGatePointAsc(RUN_ID))
                .thenReturn(List.of(
                        gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN),
                        gate(run, ResearchGatePoint.PROTOCOL_DECISION, ResearchGateBehavior.REQUIRE_HUMAN)));

        var snapshot = service.getSnapshot(PROJECT_ID, RUN_ID);

        assertThat(snapshot.currentStage()).isEqualTo(ResearchRunStage.PROTOCOL_PLANNING);
        assertThat(snapshot.sourceCounts().candidateSources()).isEqualTo(10);
        assertThat(snapshot.artifactReadiness())
                .filteredOn(r -> r.artifactType() == ResearchArtifactType.METHODOLOGY_REQUIREMENTS)
                .singleElement()
                .satisfies(r -> assertThat(r.readiness())
                        .isEqualTo(com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness.READY));
        assertThat(snapshot.artifactReadiness())
                .filteredOn(r -> r.artifactType() == ResearchArtifactType.MANUSCRIPT)
                .singleElement()
                .satisfies(r -> assertThat(r.readiness())
                        .isEqualTo(
                                com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness.MISSING));
        assertThat(snapshot.pendingGates()).hasSize(2);
    }

    // ------------------------------------------------------ project scoping

    @Test
    void crossProjectRun_isConcealedAsNotFound() {
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(PROJECT_ID, RUN_ID)).isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------- stop / complete

    @Test
    void stop_transitionsToStoppedAndStampsStoppedAt() {
        runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var stopped = service.stop(PROJECT_ID, RUN_ID);
        assertThat(stopped.getStatus()).isEqualTo(ResearchRunStatus.STOPPED);
        assertThat(stopped.getStoppedAt()).isNotNull();
    }

    @Test
    void complete_atFinalStageWithActiveManuscript_transitionsToCompleted() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE)));
        var completed = service.complete(PROJECT_ID, RUN_ID);
        assertThat(completed.getStatus()).isEqualTo(ResearchRunStatus.COMPLETED);
    }

    @Test
    void complete_beforeFinalStage_throwsValidation() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("final stage");
    }

    @Test
    void complete_finalStageMissingArtifact_throwsValidation() {
        runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("without an active");
    }

    // ----------------------------------------------------------------- reads

    @Test
    void getByUid_returnsRun() {
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        when(runRepository.findByProjectIdAndUid(PROJECT_ID, "RUN-1")).thenReturn(Optional.of(run));
        assertThat(service.getByUid(PROJECT_ID, "RUN-1")).isSameAs(run);
    }

    @Test
    void getByUid_missing_throwsNotFound() {
        when(runRepository.findByProjectIdAndUid(PROJECT_ID, "NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByUid(PROJECT_ID, "NOPE")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listByProject_returnsProjectRuns() {
        var run = runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(runRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(run));
        assertThat(service.listByProject(PROJECT_ID)).containsExactly(run);
    }

    @Test
    void listArtifacts_returnsRunArtifacts() {
        var run = runAt(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var a = artifact(run, ResearchArtifactType.SEARCH_LOG, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of(a));
        assertThat(service.listArtifacts(PROJECT_ID, RUN_ID)).containsExactly(a);
    }

    @Test
    void listGates_returnsRunGates() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var g = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        when(gateRepository.findByResearchRunIdOrderByGatePointAsc(RUN_ID)).thenReturn(List.of(g));
        assertThat(service.listGates(PROJECT_ID, RUN_ID)).containsExactly(g);
    }

    /** Small builder so the start tests read clearly. */
    private record StartCmd(String uid, AutonomyLevel autonomy, IntendedOutput intendedOutput) {
        com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand toCommand() {
            return new com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand(
                    PROJECT_ID, uid, autonomy, intendedOutput, java.util.Map.of());
        }
    }
}
