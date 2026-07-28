package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
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
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Split from ResearchRunDecisionSurfacesServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunDecisionSurfacesServiceAddDisclosureEntry_onStaleDisclosure_throwsConflictTest {
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
    void addDisclosureEntry_onStaleDisclosure_throwsConflict() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var stale = disclosure(run, UUID.randomUUID(), false, false, DisclosureStatus.STALE);
        var staleId = stale.getId();
        when(disclosureRepository.findById(staleId)).thenReturn(Optional.of(stale));
        var cmd = new AddDisclosureEntryCommand(
                DisclosureEntryFamily.AI_GENERATED_PART,
                null,
                null,
                null,
                "claude",
                "section 3 drafted by model",
                null,
                null,
                null);
        assertThatThrownBy(() -> service.addDisclosureEntry(PROJECT_ID, RUN_ID, staleId, cmd))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addDisclosureEntry_uncertaintyCategoryRequiredIffUnresolvedUncertainty() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var current = disclosure(run, UUID.randomUUID(), false, false, DisclosureStatus.CURRENT);
        var currentId = current.getId();
        when(disclosureRepository.findById(currentId)).thenReturn(Optional.of(current));

        // UNRESOLVED_UNCERTAINTY without a category is rejected.
        var cmd1 = new AddDisclosureEntryCommand(
                DisclosureEntryFamily.UNRESOLVED_UNCERTAINTY,
                null,
                null,
                null,
                null,
                "an access gap remains",
                null,
                null,
                null);
        assertThatThrownBy(() -> service.addDisclosureEntry(PROJECT_ID, RUN_ID, currentId, cmd1))
                .isInstanceOf(DomainValidationException.class);

        // AI_GENERATED_PART with a category set is rejected.
        var cmd2 = new AddDisclosureEntryCommand(
                DisclosureEntryFamily.AI_GENERATED_PART,
                DisclosureUncertaintyCategory.SCIENTIFIC,
                null,
                null,
                null,
                "model drafted section",
                null,
                null,
                null);
        assertThatThrownBy(() -> service.addDisclosureEntry(PROJECT_ID, RUN_ID, currentId, cmd2))
                .isInstanceOf(DomainValidationException.class);

        // Valid UNRESOLVED_UNCERTAINTY with a category succeeds.
        var saved = service.addDisclosureEntry(
                PROJECT_ID,
                RUN_ID,
                currentId,
                new AddDisclosureEntryCommand(
                        DisclosureEntryFamily.UNRESOLVED_UNCERTAINTY,
                        DisclosureUncertaintyCategory.ACCESS_GAP,
                        null,
                        null,
                        null,
                        "two sources behind paywall",
                        null,
                        null,
                        null));
        assertThat(saved.getUncertaintyCategory()).isEqualTo(DisclosureUncertaintyCategory.ACCESS_GAP);
    }

    // --------------------------------------------------- complete() gating

    @Test
    void complete_missingDisclosure_throwsValidation() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE)));
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.empty());
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.STALE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("disclosure");
    }

    @Test
    void complete_staleDisclosureOnly_throwsValidation() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.empty());
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.STALE))
                .thenReturn(Optional.of(disclosure(run, manuscript.getId(), true, true, DisclosureStatus.STALE)));
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void complete_incompleteDisclosure_missingAiParts_throwsValidation() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        // aiPartsDeclaredNone=false and no AI_GENERATED_PART entry => incomplete.
        var current = disclosure(run, manuscript.getId(), false, true, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));
        when(disclosureEntryRepository.findByDisclosureId(current.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void complete_withCurrentCompleteDisclosure_succeeds() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        // Autonomous run: AI + uncertainty covered by entries; human approvals
        // explicitly declared none (AUTO_ACCEPTED gates are not human approvals).
        var current = disclosure(run, manuscript.getId(), false, false, true, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));
        var aiEntry = new ResearchRunDisclosureEntry(
                current, DisclosureEntryFamily.AI_GENERATED_PART, "model drafted intro", "actor");
        var uncertaintyEntry = new ResearchRunDisclosureEntry(
                current, DisclosureEntryFamily.UNRESOLVED_UNCERTAINTY, "one paywalled source", "actor");
        when(disclosureEntryRepository.findByDisclosureId(current.getId()))
                .thenReturn(List.of(aiEntry, uncertaintyEntry));

        var completed = service.complete(PROJECT_ID, RUN_ID);
        assertThat(completed.getStatus()).isEqualTo(ResearchRunStatus.COMPLETED);
    }

    @Test
    void complete_missingHumanApprovalCoverage_throwsValidation() {
        // AI + uncertainty declared none, but neither a human-approval declaration
        // nor any APPROVED decision-log row => the third ADR-068 family is uncovered.
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        var current = disclosure(run, manuscript.getId(), true, true, false, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));
        when(disclosureEntryRepository.findByDisclosureId(current.getId())).thenReturn(List.of());
        when(decisionLogRepository.existsByResearchRunIdAndDecisionOutcome(
                        RUN_ID, ResearchGateDecisionOutcome.APPROVED))
                .thenReturn(false);
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void complete_humanApprovalDerivedFromDecisionLog_succeeds() {
        // A real human APPROVED gate decision covers the human-approval family even
        // without an explicit declared-none flag.
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var manuscript = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(manuscript));
        var current = disclosure(run, manuscript.getId(), true, true, false, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));
        when(disclosureEntryRepository.findByDisclosureId(current.getId())).thenReturn(List.of());
        when(decisionLogRepository.existsByResearchRunIdAndDecisionOutcome(
                        RUN_ID, ResearchGateDecisionOutcome.APPROVED))
                .thenReturn(true);

        var completed = service.complete(PROJECT_ID, RUN_ID);
        assertThat(completed.getStatus()).isEqualTo(ResearchRunStatus.COMPLETED);
    }

    @Test
    void complete_autoAcceptedGatesDoNotSubstituteForDisclosure() {
        // Even with all gates auto-accepted (autonomous run), completion still
        // requires a disclosure: AUTO_ACCEPTED is not human approval.
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE)));
        // The run has auto-accepted gate decisions (autonomous default) but no disclosure.
        when(decisionLogRepository.existsByResearchRunIdAndDecisionOutcome(
                        RUN_ID, ResearchGateDecisionOutcome.AUTO_ACCEPTED))
                .thenReturn(true);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.empty());
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.STALE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(PROJECT_ID, RUN_ID))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("disclosure");
    }

    // ------------------------------------ run-scoped reference validation

    @Test
    void addReviewComment_artifactTargetNotInRun_throwsNotFound() {
        runAt(ResearchRunStage.SCREENING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var foreignArtifactId = UUID.randomUUID();
        when(artifactRepository.existsByIdAndResearchRunId(foreignArtifactId, RUN_ID))
                .thenReturn(false);
        var cmd = new AddReviewCommentCommand(
                ReviewCommentTarget.ARTIFACT,
                null,
                null,
                foreignArtifactId,
                null,
                "comment on another run's artifact",
                ReviewCommentProvenance.HUMAN_REVIEW);
        assertThatThrownBy(() -> service.addReviewComment(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(NotFoundException.class);
        verify(reviewCommentRepository, never()).save(any());
    }

    @Test
    void addReviewComment_artifactTargetInRun_succeeds() {
        runAt(ResearchRunStage.SCREENING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var artifactId = UUID.randomUUID();
        when(artifactRepository.existsByIdAndResearchRunId(artifactId, RUN_ID)).thenReturn(true);
        var added = service.addReviewComment(
                PROJECT_ID,
                RUN_ID,
                new AddReviewCommentCommand(
                        ReviewCommentTarget.ARTIFACT,
                        null,
                        null,
                        artifactId,
                        null,
                        "valid same-run artifact comment",
                        ReviewCommentProvenance.HUMAN_REVIEW));
        assertThat(added.getTargetArtifactId()).isEqualTo(artifactId);
    }

    @Test
    void addRationaleEntry_artifactRefNotInRun_throwsNotFound() {
        runAt(ResearchRunStage.CHARTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var foreignArtifactId = UUID.randomUUID();
        when(artifactRepository.existsByIdAndResearchRunId(foreignArtifactId, RUN_ID))
                .thenReturn(false);
        var cmd = new AddRationaleEntryCommand(
                ResearchRunStage.CHARTING,
                ResearchArtifactType.CHARTING_DATA,
                foreignArtifactId,
                1,
                null,
                RationaleEntryKind.CHARTED_VALUE,
                RationaleEvidenceBasis.CHARTED_CELL,
                RationaleProvenance.AGENT_RECOMMENDATION,
                "subject",
                "summary",
                null,
                null);
        assertThatThrownBy(() -> service.addRationaleEntry(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(NotFoundException.class);
        verify(rationaleRepository, never()).save(any());
    }
}
