package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySourceRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunReviewCommentRepository;
import com.keplerops.groundcontrol.domain.research.service.AddDisclosureEntryCommand;
import com.keplerops.groundcontrol.domain.research.service.AddRationaleEntryCommand;
import com.keplerops.groundcontrol.domain.research.service.AddReviewCommentCommand;
import com.keplerops.groundcontrol.domain.research.service.CreateDisclosureCommand;
import com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand;
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
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

/** Split from ResearchRunDecisionSurfacesServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunDecisionSurfacesServiceAddRationaleEntry_artifactTypeMismatch_throwsValidationTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchRunArtifactRepository artifactRepository;

    @Mock
    private ResearchRunGateRepository gateRepository;

    @Mock
    private ResearchRunGateDecisionLogRepository decisionLogRepository;

    @Mock
    private ResearchRunReviewCommentRepository reviewCommentRepository;

    @Mock
    private ResearchRunRationaleEntryRepository rationaleRepository;

    @Mock
    private ResearchRunDisclosureRepository disclosureRepository;

    @Mock
    private ResearchRunDisclosureEntryRepository disclosureEntryRepository;

    @Mock
    private ResearchIntakeRepository intakeRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ResearchRunMethodologySelectionRepository selectionRepository;

    @Mock
    private ResearchRunMethodologySourceRepository sourceRepository;

    private ResearchRunService service;
    private Project project;

    // Real catalog (loads classpath:research/methodology-catalog.yaml).
    private final MethodologyCatalog methodologyCatalog = new MethodologyCatalog();

    @BeforeEach
    void setUp() {
        service = new ResearchRunService(
                runRepository,
                artifactRepository,
                gateRepository,
                decisionLogRepository,
                reviewCommentRepository,
                rationaleRepository,
                disclosureRepository,
                disclosureEntryRepository,
                intakeRepository,
                projectService,
                selectionRepository,
                sourceRepository,
                methodologyCatalog,
                mock(
                        com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository
                                .class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntryRepository.class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntrySourceLinkRepository.class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractRejectedAlternativeRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository.class));
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionLogRepository.save(any())).thenAnswer(inv -> {
            ResearchRunGateDecisionLog l = inv.getArgument(0);
            if (l.getId() == null) {
                TestUtil.setField(l, "id", UUID.randomUUID());
            }
            return l;
        });
        when(reviewCommentRepository.save(any())).thenAnswer(inv -> {
            ResearchRunReviewComment c = inv.getArgument(0);
            if (c.getId() == null) {
                TestUtil.setField(c, "id", UUID.randomUUID());
            }
            return c;
        });
        when(rationaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(disclosureRepository.save(any())).thenAnswer(inv -> {
            ResearchRunDisclosure d = inv.getArgument(0);
            if (d.getId() == null) {
                TestUtil.setField(d, "id", UUID.randomUUID());
            }
            return d;
        });
        when(disclosureEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        run.setOwnerActor("owner-actor");
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

    private ResearchRunDisclosure disclosure(
            ResearchRun run, UUID manuscriptId, boolean aiNone, boolean uncertaintyNone, DisclosureStatus status) {
        return disclosure(run, manuscriptId, aiNone, uncertaintyNone, false, status);
    }

    private ResearchRunDisclosure disclosure(
            ResearchRun run,
            UUID manuscriptId,
            boolean aiNone,
            boolean uncertaintyNone,
            boolean humanNone,
            DisclosureStatus status) {
        var d = new ResearchRunDisclosure(run, manuscriptId, 1, aiNone, uncertaintyNone, humanNone, "owner-actor");
        TestUtil.setField(d, "id", UUID.randomUUID());
        TestUtil.setField(d, "status", status);
        return d;
    }

    @Test
    void addRationaleEntry_artifactTypeMismatch_throwsValidation() {
        var run = runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var chartingArtifact = artifact(run, ResearchArtifactType.CHARTING_DATA, ResearchArtifactStatus.SUPERSEDED);
        when(artifactRepository.findByIdAndResearchRunId(chartingArtifact.getId(), RUN_ID))
                .thenReturn(Optional.of(chartingArtifact));
        // Declares MANUSCRIPT but the referenced artifact is CHARTING_DATA.
        var cmd = new AddRationaleEntryCommand(
                ResearchRunStage.SYNTHESIS,
                ResearchArtifactType.MANUSCRIPT,
                chartingArtifact.getId(),
                null,
                null,
                RationaleEntryKind.SYNTHESIS_CLAIM,
                RationaleEvidenceBasis.CHARTED_CELL,
                RationaleProvenance.AGENT_RECOMMENDATION,
                "subject",
                "summary",
                null,
                null);
        assertThatThrownBy(() -> service.addRationaleEntry(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
        verify(rationaleRepository, never()).save(any());
    }

    @Test
    void addRationaleEntry_gatePointNotGuardingStage_throwsValidation() {
        runAt(ResearchRunStage.CHARTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        // METHOD_DECISION guards METHODOLOGY_SELECTION, not CHARTING.
        var cmd = new AddRationaleEntryCommand(
                ResearchRunStage.CHARTING,
                null,
                null,
                null,
                ResearchGatePoint.METHOD_DECISION,
                RationaleEntryKind.CHARTED_VALUE,
                RationaleEvidenceBasis.CHARTED_CELL,
                RationaleProvenance.AGENT_RECOMMENDATION,
                "subject",
                "summary",
                null,
                null);
        assertThatThrownBy(() -> service.addRationaleEntry(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
        verify(rationaleRepository, never()).save(any());
    }

    @Test
    void resolveGate_decisionLog_capturesActiveArtifactAttempt() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var g = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(g));
        var methodArtifact =
                artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        TestUtil.setField(methodArtifact, "attemptNo", 3);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(methodArtifact));

        service.resolveGate(
                PROJECT_ID,
                RUN_ID,
                new GateDecisionCommand(
                        ResearchGatePoint.METHOD_DECISION,
                        ResearchGateDecisionOutcome.APPROVED,
                        null,
                        "ok",
                        null,
                        null,
                        null,
                        null,
                        null));

        var captor = ArgumentCaptor.forClass(ResearchRunGateDecisionLog.class);
        verify(decisionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getArtifactAttemptNo()).isEqualTo(3);
    }

    @Test
    void addDisclosureEntry_crossRunRationaleRef_throwsNotFound() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var current = disclosure(run, UUID.randomUUID(), false, false, DisclosureStatus.CURRENT);
        var currentId = current.getId();
        when(disclosureRepository.findById(currentId)).thenReturn(Optional.of(current));
        var foreignRationaleId = UUID.randomUUID();
        when(rationaleRepository.existsByIdAndResearchRunId(foreignRationaleId, RUN_ID))
                .thenReturn(false);
        var cmd = new AddDisclosureEntryCommand(
                DisclosureEntryFamily.AI_GENERATED_PART,
                null,
                null,
                null,
                "claude",
                "section drafted by model",
                foreignRationaleId,
                null,
                null);
        assertThatThrownBy(() -> service.addDisclosureEntry(PROJECT_ID, RUN_ID, currentId, cmd))
                .isInstanceOf(NotFoundException.class);
        verify(disclosureEntryRepository, never()).save(any());
    }

    // --------------------------------- disclosure single-current invariant

    @Test
    void createDisclosure_finalArtifactIdMismatch_throwsValidation() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        var cmd = new CreateDisclosureCommand(UUID.randomUUID(), 1, false, false, false);
        assertThatThrownBy(() -> service.createDisclosure(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("finalArtifactId");
        verify(disclosureRepository, never()).save(any());
    }

    @Test
    void createDisclosure_existingCurrentSameManuscript_isIdempotent() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        var existing = disclosure(run, manuscript.getId(), false, false, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(existing));

        var result = service.createDisclosure(
                PROJECT_ID, RUN_ID, new CreateDisclosureCommand(manuscript.getId(), 1, false, false, false));

        assertThat(result).isSameAs(existing);
        verify(disclosureRepository, never()).save(any());
    }

    @Test
    void createDisclosure_existingCurrentDifferentManuscript_throwsConflict() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        var staleCurrentForOldManuscript = disclosure(run, UUID.randomUUID(), false, false, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(staleCurrentForOldManuscript));

        var cmd = new CreateDisclosureCommand(manuscript.getId(), 1, false, false, false);
        assertThatThrownBy(() -> service.createDisclosure(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(ConflictException.class);
        verify(disclosureRepository, never()).save(any());
    }

    // ------------------------------------------------- happy paths + reads

    @Test
    void createDisclosure_noExistingCurrent_createsCurrentDisclosure() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.empty());

        var created = service.createDisclosure(
                PROJECT_ID, RUN_ID, new CreateDisclosureCommand(manuscript.getId(), 1, true, false, true));

        assertThat(created.getFinalArtifactId()).isEqualTo(manuscript.getId());
        assertThat(created.getStatus()).isEqualTo(DisclosureStatus.CURRENT);
        assertThat(created.isAiPartsDeclaredNone()).isTrue();
        assertThat(created.isHumanApprovalsDeclaredNone()).isTrue();
        verify(disclosureRepository).save(any());
    }

    @Test
    void addRationaleEntry_withResolvedConsistentArtifact_persists() {
        var run = runAt(ResearchRunStage.CHARTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var chartingArtifact = artifact(run, ResearchArtifactType.CHARTING_DATA, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByIdAndResearchRunId(chartingArtifact.getId(), RUN_ID))
                .thenReturn(Optional.of(chartingArtifact));

        var saved = service.addRationaleEntry(
                PROJECT_ID,
                RUN_ID,
                new AddRationaleEntryCommand(
                        ResearchRunStage.CHARTING,
                        ResearchArtifactType.CHARTING_DATA,
                        chartingArtifact.getId(),
                        1,
                        null,
                        RationaleEntryKind.CHARTED_VALUE,
                        RationaleEvidenceBasis.CHARTED_CELL,
                        RationaleProvenance.AGENT_RECOMMENDATION,
                        "row-3",
                        "charted from table 2",
                        null,
                        null));

        assertThat(saved.getArtifactId()).isEqualTo(chartingArtifact.getId());
        assertThat(saved.getKind()).isEqualTo(RationaleEntryKind.CHARTED_VALUE);
    }

    @Test
    void addReviewComment_runTarget_persistsOpenComment() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var added = service.addReviewComment(
                PROJECT_ID,
                RUN_ID,
                new AddReviewCommentCommand(
                        ReviewCommentTarget.RUN,
                        null,
                        null,
                        null,
                        null,
                        "overall looks sound",
                        ReviewCommentProvenance.HUMAN_REVIEW));
        assertThat(added.getStatus()).isEqualTo(ReviewCommentStatus.OPEN);
        assertThat(added.getTargetType()).isEqualTo(ReviewCommentTarget.RUN);
    }

    @Test
    void readSurfaces_areProjectScopedAndReturnRepositoryRows() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        when(decisionLogRepository.findByResearchRunIdOrderByDecidedAtAsc(RUN_ID))
                .thenReturn(List.of());
        when(reviewCommentRepository.findByResearchRunIdOrderByCreatedAtAsc(RUN_ID))
                .thenReturn(List.of());
        when(rationaleRepository.findByResearchRunIdOrderByRecordedAtAsc(RUN_ID))
                .thenReturn(List.of());

        assertThat(service.listGateDecisionLog(PROJECT_ID, RUN_ID)).isEmpty();
        assertThat(service.listReviewComments(PROJECT_ID, RUN_ID)).isEmpty();
        assertThat(service.listRationale(PROJECT_ID, RUN_ID)).isEmpty();
        // All three reads must pass through the project-scoped run-ownership check.
        verify(runRepository, times(3)).findByIdAndProjectId(RUN_ID, PROJECT_ID);
    }

    @Test
    void getDisclosure_returnsCurrentAndListsEntries() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var current = disclosure(run, UUID.randomUUID(), false, false, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));
        when(disclosureRepository.findById(current.getId())).thenReturn(Optional.of(current));
        var entry = new ResearchRunDisclosureEntry(
                current, DisclosureEntryFamily.AI_GENERATED_PART, "intro drafted by model", "actor");
        when(disclosureEntryRepository.findByDisclosureId(current.getId())).thenReturn(List.of(entry));

        assertThat(service.getDisclosure(PROJECT_ID, RUN_ID)).isSameAs(current);
        assertThat(service.listDisclosureEntries(PROJECT_ID, RUN_ID, current.getId()))
                .containsExactly(entry);
    }

    @Test
    void getDisclosure_whenNoCurrent_throwsNotFound() {
        runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDisclosure(PROJECT_ID, RUN_ID)).isInstanceOf(NotFoundException.class);
    }
}
