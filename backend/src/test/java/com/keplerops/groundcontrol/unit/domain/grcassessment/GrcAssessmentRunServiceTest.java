package com.keplerops.groundcontrol.unit.domain.grcassessment;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelSnapshotView;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationResult;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.derivation.service.CreateDerivationRunCommand;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationRunResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.grcassessment.model.GrcAssessmentRun;
import com.keplerops.groundcontrol.domain.grcassessment.repository.GrcAssessmentRunRepository;
import com.keplerops.groundcontrol.domain.grcassessment.service.CreateGrcAssessmentRunCommand;
import com.keplerops.groundcontrol.domain.grcassessment.service.GrcAssessmentRunService;
import com.keplerops.groundcontrol.domain.grcassessment.service.ReviewGrcAssessmentRunCommand;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentRunState;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationResult;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrcAssessmentRunServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111129");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222221129");
    private static final UUID DERIVATION_RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333331129");
    private static final UUID SNAPSHOT_ID = UUID.fromString("44444444-4444-4444-4444-444444441129");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Mock
    private GrcAssessmentRunRepository runRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private DerivationService derivationService;

    @Mock
    private ThreatEnumerationService threatEnumerationService;

    @Mock
    private ControlIdentificationService controlIdentificationService;

    private Project project;
    private GrcAssessmentRunService service;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        service = new GrcAssessmentRunService(
                runRepository,
                projectService,
                derivationService,
                threatEnumerationService,
                controlIdentificationService);
        lenient().when(projectService.getById(PROJECT_ID)).thenReturn(project);
        lenient().when(runRepository.save(any())).thenAnswer(invocation -> {
            GrcAssessmentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                setField(run, "id", RUN_ID);
                setField(run, "createdAt", Instant.parse("2026-07-04T00:00:00Z"));
            }
            setField(run, "updatedAt", Instant.parse("2026-07-04T00:00:00Z"));
            return run;
        });
    }

    @Test
    void previewRunRecordsPartitionsWithoutCommittingGraphEffects() {
        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                List.of(),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(),
                "nist-800-53",
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.REQUEST_REVIEW,
                "gc-1129-preview",
                50));

        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.READY_FOR_REVIEW);
        assertThat(run.getPartitionCount()).isEqualTo(1);
        assertThat(run.getGraphEffectCount()).isZero();
        assertThat(run.getPartitions()).singleElement().satisfies(partition -> {
            assertThat(partition).containsEntry("partitionKey", "whole-project");
            assertThat(partition).containsEntry("scopeType", "WHOLE_PROJECT");
        });
        verifyNoInteractions(derivationService, threatEnumerationService, controlIdentificationService);
    }

    @Test
    void approvedWholeProjectModelRunExecutesOneFullRepoDerivationAndRecordsGraphEffects() {
        when(derivationService.run(any())).thenReturn(derivationResult(DerivationScopeMode.FULL_REPO));

        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                List.of(),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(),
                "nist-800-53",
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "gc-1129-approved",
                50));

        var command = ArgumentCaptor.forClass(CreateDerivationRunCommand.class);
        verify(derivationService).run(command.capture());
        assertThat(command.getValue().scopeMode()).isEqualTo(DerivationScopeMode.FULL_REPO);
        assertThat(command.getValue().paths()).isEmpty();
        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        assertThat(run.getGraphEffectCount()).isEqualTo(2);
        assertThat(run.getGraphEffects())
                .extracting(effect -> effect.get("effectType"))
                .containsExactly("DERIVATION_RUN", "ARCHITECTURE_MODEL_SNAPSHOT");
    }

    @Test
    void approvedBoundaryRunDeduplicatesPartitionsAndExecutesDeterministically() {
        when(derivationService.run(any())).thenReturn(derivationResult(DerivationScopeMode.PATH_SET));

        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.REASSESS,
                GrcAssessmentScopeType.BOUNDARY,
                List.of("payments", "identity", "payments"),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(
                        new BoundaryDeclaration(
                                "payments", "Payments", null, List.of("backend/payments/**"), List.of("application")),
                        new BoundaryDeclaration(
                                "identity", "Identity", null, List.of("backend/identity/**"), List.of("application"))),
                "nist-800-53",
                "1.0.0",
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "gc-1129-boundaries",
                50));

        assertThat(run.getPartitionCount()).isEqualTo(3);
        assertThat(run.getDedupedPartitionCount()).isEqualTo(2);
        assertThat(run.getDuplicatePartitionCount()).isEqualTo(1);
        assertThat(run.getPartitions())
                .extracting(partition -> partition.get("partitionKey"))
                .containsExactly("boundary:identity", "boundary:payments");

        var command = ArgumentCaptor.forClass(CreateDerivationRunCommand.class);
        verify(derivationService, org.mockito.Mockito.times(2)).run(command.capture());
        assertThat(command.getAllValues())
                .extracting(CreateDerivationRunCommand::paths)
                .containsExactly(List.of("backend/identity/**"), List.of("backend/payments/**"));
    }

    @Test
    void rejectedCreateRunPersistsRejectedStateWithoutGraphEffects() {
        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                List.of(),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(),
                null,
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.REJECTED,
                "gc-1129-rejected-create",
                50));

        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.REJECTED);
        assertThat(run.getGraphEffectCount()).isZero();
        verifyNoInteractions(derivationService, threatEnumerationService, controlIdentificationService);
    }

    @Test
    void reviewRunCanRejectReadyForReviewRun() {
        var ready = readyRun(GrcAssessmentMode.MODEL, GrcAssessmentScopeType.WHOLE_PROJECT);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(ready));

        var reviewed = service.reviewRun(new ReviewGrcAssessmentRunCommand(
                PROJECT_ID, RUN_ID, GrcAssessmentReviewDecision.REJECTED, "reviewer", "out of scope"));

        assertThat(reviewed.getState()).isEqualTo(GrcAssessmentRunState.REJECTED);
        assertThat(reviewed.getReviewedBy()).isEqualTo("reviewer");
        assertThat(reviewed.getReviewRationale()).isEqualTo("out of scope");
        verify(runRepository).save(ready);
        verifyNoInteractions(derivationService, threatEnumerationService, controlIdentificationService);
    }

    @Test
    void reviewRunApprovesReadyBoundaryRunUsingPersistedBoundaryMetadata() {
        when(derivationService.run(any())).thenReturn(derivationResult(DerivationScopeMode.PATH_SET));
        var ready = readyRun(GrcAssessmentMode.REASSESS, GrcAssessmentScopeType.BOUNDARY);
        ready.recordPartitions(
                1,
                List.of(Map.of(
                        "partitionKey",
                        "boundary:payments",
                        "scopeType",
                        "BOUNDARY",
                        "scopeValue",
                        "payments",
                        "paths",
                        List.of("backend/payments/**"))),
                1);
        ready.setDeclaredBoundaries(List.of(Map.of(
                "key",
                "payments",
                "name",
                "Payments",
                "description",
                "Payment processing",
                "pathSelectors",
                List.of("backend/payments/**"),
                "surfaces",
                List.of("application"))));
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(ready));

        var reviewed = service.reviewRun(new ReviewGrcAssessmentRunCommand(
                PROJECT_ID, RUN_ID, GrcAssessmentReviewDecision.APPROVED, "reviewer", "ship it"));

        var command = ArgumentCaptor.forClass(CreateDerivationRunCommand.class);
        verify(derivationService).run(command.capture());
        assertThat(command.getValue().declaredBoundaries()).singleElement().satisfies(boundary -> {
            assertThat(boundary.key()).isEqualTo("payments");
            assertThat(boundary.name()).isEqualTo("Payments");
            assertThat(boundary.description()).isEqualTo("Payment processing");
        });
        assertThat(reviewed.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        assertThat(reviewed.getGraphEffectCount()).isEqualTo(2);
    }

    @Test
    void approvedBoundaryWithoutDeclaredPathRecordsScopeOnlyEffect() {
        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.BOUNDARY,
                List.of("missing-boundary"),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(),
                null,
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "gc-1129-boundary-no-path",
                50));

        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        assertThat(run.getGraphEffects()).singleElement().satisfies(effect -> {
            assertThat(effect).containsEntry("effectType", "SCOPE_RECORDED");
            assertThat(effect).containsEntry("scopeType", "BOUNDARY");
        });
        verify(derivationService, never()).run(any());
    }

    @Test
    void approvedReScreenUsesEnumerationAndControlIdentificationWithoutDerivation() {
        when(threatEnumerationService.enumerateLatest(PROJECT_ID, "nist-800-53", "1.0.0"))
                .thenReturn(new ThreatEnumerationResult(
                        "threat-enumeration/v1",
                        "nist-800-53",
                        "1.0.0",
                        "sha256:abc",
                        null,
                        null,
                        List.of(),
                        List.of()));
        when(controlIdentificationService.identifyForLatestSnapshot(PROJECT_ID, "nist-800-53", "1.0.0"))
                .thenReturn(new ControlIdentificationResult(
                        "control-identification/v1", "default", "1", List.of(), List.of()));

        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.RE_SCREEN,
                GrcAssessmentScopeType.PACKAGE_PATH_SET,
                List.of("mcp/ground-control", "backend/src/main/java"),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                "nist-800-53",
                "1.0.0",
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "gc-1129-rescreen",
                50));

        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        assertThat(run.getCommitSha()).isNull();
        assertThat(run.getLanguages()).isEmpty();
        assertThat(run.getSurfaces()).isEmpty();
        assertThat(run.getGraphEffects())
                .extracting(effect -> effect.get("effectType"))
                .containsExactly("THREAT_ENUMERATION", "CONTROL_IDENTIFICATION");
        verify(derivationService, never()).run(any());
    }

    @Test
    void reScreenWithoutThreatPackRecordsNoThreatPackEffect() {
        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.RE_SCREEN,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "gc-1129-rescreen-no-pack",
                50));

        assertThat(run.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        assertThat(run.getGraphEffects()).singleElement().satisfies(effect -> {
            assertThat(effect).containsEntry("effectType", "RE_SCREEN");
            assertThat(effect).containsEntry("reason", "no_threat_pack");
        });
        verifyNoInteractions(derivationService, threatEnumerationService, controlIdentificationService);
    }

    @Test
    void idempotencyKeyReturnsExistingRunWithoutReExecuting() {
        var existing = new GrcAssessmentRun(
                project,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                GrcAssessmentReviewPolicy.REQUIRED,
                "same-key");
        setField(existing, "id", RUN_ID);
        existing.recordPartitions(1, List.of(java.util.Map.of("partitionKey", "whole-project")), 1);
        when(runRepository.findByProjectIdAndIdempotencyKey(PROJECT_ID, "same-key"))
                .thenReturn(Optional.of(existing));

        var run = service.createRun(new CreateGrcAssessmentRunCommand(
                PROJECT_ID,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                List.of(),
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                List.of(),
                null,
                null,
                GrcAssessmentReviewPolicy.REQUIRED,
                GrcAssessmentReviewDecision.APPROVED,
                "same-key",
                50));

        assertThat(run).isSameAs(existing);
        verify(derivationService, never()).run(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void getRunAndListRunsEnforceProjectScopeAndBoundedLimits() {
        var ready = readyRun(GrcAssessmentMode.MODEL, GrcAssessmentScopeType.WHOLE_PROJECT);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(ready));
        when(runRepository.findByProjectIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(ready));

        assertThat(service.getRun(PROJECT_ID, RUN_ID)).isSameAs(ready);
        assertThat(service.listRuns(PROJECT_ID, 0)).containsExactly(ready);
        assertThat(service.listRuns(PROJECT_ID, 150)).containsExactly(ready);

        var page = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(runRepository, org.mockito.Mockito.times(2)).findByProjectIdOrderByCreatedAtDesc(any(), page.capture());
        assertThat(page.getAllValues())
                .extracting(org.springframework.data.domain.Pageable::getPageSize)
                .containsExactly(25, 100);

        assertThatThrownBy(() -> service.getRun(PROJECT_ID, UUID.randomUUID()))
                .hasMessageContaining("GRC assessment run not found");
    }

    @Test
    void createRunRejectsInvalidInputsBeforePersisting() {
        assertThatThrownBy(() -> service.createRun(new CreateGrcAssessmentRunCommand(
                        PROJECT_ID,
                        null,
                        GrcAssessmentScopeType.WHOLE_PROJECT,
                        List.of(),
                        COMMIT,
                        null,
                        List.of("java"),
                        List.of("application"),
                        List.of(),
                        null,
                        null,
                        GrcAssessmentReviewPolicy.REQUIRED,
                        GrcAssessmentReviewDecision.REQUEST_REVIEW,
                        null,
                        50)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("mode is required");

        assertThatThrownBy(() -> service.createRun(new CreateGrcAssessmentRunCommand(
                        PROJECT_ID,
                        GrcAssessmentMode.MODEL,
                        GrcAssessmentScopeType.WHOLE_PROJECT,
                        List.of(),
                        "not-a-sha",
                        null,
                        List.of("java"),
                        List.of("application"),
                        List.of(),
                        null,
                        null,
                        GrcAssessmentReviewPolicy.REQUIRED,
                        GrcAssessmentReviewDecision.REQUEST_REVIEW,
                        null,
                        50)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("commitSha must be");

        assertThatThrownBy(() -> service.createRun(new CreateGrcAssessmentRunCommand(
                        PROJECT_ID,
                        GrcAssessmentMode.MODEL,
                        GrcAssessmentScopeType.PACKAGE_PATH_SET,
                        List.of(),
                        COMMIT,
                        null,
                        List.of("java"),
                        List.of("application"),
                        List.of(),
                        null,
                        null,
                        GrcAssessmentReviewPolicy.REQUIRED,
                        GrcAssessmentReviewDecision.REQUEST_REVIEW,
                        null,
                        50)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("scopeValues is required");

        assertThatThrownBy(() -> service.createRun(new CreateGrcAssessmentRunCommand(
                        PROJECT_ID,
                        GrcAssessmentMode.MODEL,
                        GrcAssessmentScopeType.PACKAGE_PATH_SET,
                        List.of("backend/**", "mcp/**"),
                        COMMIT,
                        null,
                        List.of("java"),
                        List.of("application"),
                        List.of(),
                        null,
                        null,
                        GrcAssessmentReviewPolicy.REQUIRED,
                        GrcAssessmentReviewDecision.REQUEST_REVIEW,
                        null,
                        1)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("partition count exceeds partitionLimit");

        verify(runRepository, never()).save(any());
    }

    @Test
    void reviewRunRejectsMutationAfterGraphEffectsCommitted() {
        var committed = new GrcAssessmentRun(
                project,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.WHOLE_PROJECT,
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                GrcAssessmentReviewPolicy.REQUIRED,
                "committed-key");
        setField(committed, "id", RUN_ID);
        committed.recordPartitions(1, List.of(java.util.Map.of("partitionKey", "whole-project")), 1);
        committed.recordGraphEffects(List.of(java.util.Map.of("effectType", "DERIVATION_RUN")));
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(committed));

        assertThatThrownBy(() -> service.reviewRun(new ReviewGrcAssessmentRunCommand(
                        PROJECT_ID, RUN_ID, GrcAssessmentReviewDecision.REJECTED, "reviewer", "too late")))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("READY_FOR_REVIEW");

        assertThat(committed.getState()).isEqualTo(GrcAssessmentRunState.COMMITTED);
        verify(runRepository, never()).save(any());
        verifyNoInteractions(derivationService, threatEnumerationService, controlIdentificationService);
    }

    private GrcAssessmentRun readyRun(GrcAssessmentMode mode, GrcAssessmentScopeType scopeType) {
        var run = new GrcAssessmentRun(
                project,
                mode,
                scopeType,
                mode == GrcAssessmentMode.RE_SCREEN ? null : COMMIT,
                null,
                mode == GrcAssessmentMode.RE_SCREEN ? List.of() : List.of("java"),
                mode == GrcAssessmentMode.RE_SCREEN ? List.of() : List.of("application"),
                GrcAssessmentReviewPolicy.REQUIRED,
                "ready-key");
        setField(run, "id", RUN_ID);
        run.recordPartitions(1, List.of(Map.of("partitionKey", "whole-project", "paths", List.of())), 1);
        return run;
    }

    private DerivationRunResult derivationResult(DerivationScopeMode mode) {
        var derivationRun = new DerivationRun(
                project,
                mode,
                COMMIT,
                null,
                List.of(),
                List.of("java"),
                List.of("application"),
                "codex",
                Instant.now(),
                1);
        setField(derivationRun, "id", DERIVATION_RUN_ID);
        var snapshot = new ArchitectureModelSnapshot(project, derivationRun, "model/1", COMMIT, "DERIVATION", "codex");
        setField(snapshot, "id", SNAPSHOT_ID);
        return new DerivationRunResult(
                derivationRun, List.of(), List.of(), new ArchitectureModelSnapshotView(snapshot, List.of()), null);
    }
}
