package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import com.keplerops.groundcontrol.domain.research.model.GateRecommendationProvenance;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
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
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
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
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import com.keplerops.groundcontrol.domain.research.service.CreateDisclosureCommand;
import com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand;
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.ResolveReviewCommentCommand;
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
 * GC-RSCH-F004/F034/N012/N013 — behavioral unit tests for the #1001 decision
 * surfaces on {@link ResearchRunService}: the append-only gate decision log, run
 * review comments, the rationale ledger, and the manuscript disclosure that
 * gates completion (ADR-066 / ADR-067 / ADR-068). Mirrors ResearchRunServiceTest
 * (Mockito mocks, lenient settings, ActorHolder-backed actor).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunDecisionSurfacesServiceTest {

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
                methodologyCatalog);
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

    /**
     * Stub an active methodology selection with one required source already in READ
     * state so the GC-RSCH-F006 coverage gate passes when a METHODOLOGY_REQUIREMENTS
     * artifact is recorded. Required sources are declared at selection time and must
     * reach READ before the gate opens.
     * Call this in any test that records that artifact type.
     */
    private void withPassingCoverageGate(ResearchRun run) {
        // Mirror a real catalog selection: method "systematic" with its two derived
        // required sources already transitioned to READ so the F006 coverage gate opens.
        var sel = new ResearchRunMethodologySelection(run, "systematic", "actor");
        sel.setProfileVersion("1");
        sel.setCatalogVersion("1");
        var selId = UUID.randomUUID();
        TestUtil.setField(sel, "id", selId);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        var sources = new java.util.ArrayList<ResearchRunMethodologySource>();
        for (var ref : List.of("FRM9HPNG", "MJX3HCT5")) {
            var src = new ResearchRunMethodologySource(sel, ref, true, "actor");
            TestUtil.setField(src, "id", UUID.randomUUID());
            TestUtil.setField(src, "state", MethodologySourceState.READ);
            sources.add(src);
        }
        when(sourceRepository.findBySelectionId(selId)).thenReturn(sources);
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

    // ---------------------------------------------------------- decision log

    @Test
    void resolveGate_appendsDecisionLogRow() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var g = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(g));

        service.resolveGate(
                PROJECT_ID,
                RUN_ID,
                new GateDecisionCommand(
                        ResearchGatePoint.METHOD_DECISION,
                        ResearchGateDecisionOutcome.APPROVED,
                        "opt-1",
                        "looks good",
                        "rec-opt",
                        "agent recommended opt-1",
                        GateRecommendationProvenance.AGENT,
                        "question-key",
                        "action-7"));

        var captor = ArgumentCaptor.forClass(ResearchRunGateDecisionLog.class);
        verify(decisionLogRepository).save(captor.capture());
        var logged = captor.getValue();
        assertThat(logged.getGatePoint()).isEqualTo(ResearchGatePoint.METHOD_DECISION);
        assertThat(logged.getGuardedStage()).isEqualTo(ResearchRunStage.METHODOLOGY_SELECTION);
        assertThat(logged.getDecisionOutcome()).isEqualTo(ResearchGateDecisionOutcome.APPROVED);
        assertThat(logged.getRecommendationProvenance()).isEqualTo(GateRecommendationProvenance.AGENT);
        assertThat(logged.getRecommendationOptionId()).isEqualTo("rec-opt");
        assertThat(logged.getQuestionKey()).isEqualTo("question-key");
        assertThat(logged.getSourceActionId()).isEqualTo("action-7");
        assertThat(logged.getDecidedAt()).isNotNull();
    }

    @Test
    void advanceStage_autonomousDefault_appendsAutoAcceptedDecisionLogWithOwnerActor() {
        var run =
                runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(
                        artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)));
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(
                        gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.AUTONOMOUS_DEFAULT)));

        service.advanceStage(PROJECT_ID, RUN_ID, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));

        var captor = ArgumentCaptor.forClass(ResearchRunGateDecisionLog.class);
        verify(decisionLogRepository).save(captor.capture());
        var logged = captor.getValue();
        assertThat(logged.getDecisionOutcome()).isEqualTo(ResearchGateDecisionOutcome.AUTO_ACCEPTED);
        assertThat(logged.getRecommendationProvenance()).isNull();
        assertThat(logged.getPolicyBasis()).isEqualTo("AUTONOMOUS_DEFAULT");
        assertThat(logged.getDecisionActor()).isEqualTo("owner-actor");
    }

    @Test
    void recordArtifact_reworkReopeningGate_doesNotDeletePriorDecisionLog() {
        var run = runAt(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var prior = artifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(prior));
        var resolvedGate = gate(run, ResearchGatePoint.METHOD_DECISION, ResearchGateBehavior.REQUIRE_HUMAN);
        resolvedGate.resolve(ResearchGateDecisionOutcome.APPROVED, null, "ok", "server-actor");
        when(gateRepository.findByResearchRunIdAndGatePoint(RUN_ID, ResearchGatePoint.METHOD_DECISION))
                .thenReturn(Optional.of(resolvedGate));
        withPassingCoverageGate(run);

        service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new RecordArtifactCommand(
                        ResearchArtifactType.METHODOLOGY_REQUIREMENTS,
                        "loc-v2",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        // Rework reopens the gate but the append-only decision log is never mutated/deleted.
        verify(decisionLogRepository, never()).delete(any());
        verify(decisionLogRepository, never()).deleteById(any());
    }

    // -------------------------------------------------------- review comments

    @Test
    void addReviewComment_resolveReopenLifecycle() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var added = service.addReviewComment(
                PROJECT_ID,
                RUN_ID,
                new AddReviewCommentCommand(
                        ReviewCommentTarget.STAGE,
                        null,
                        ResearchRunStage.SYNTHESIS,
                        null,
                        null,
                        "please double-check the inclusion threshold",
                        ReviewCommentProvenance.HUMAN_REVIEW));
        assertThat(added.getStatus()).isEqualTo(ReviewCommentStatus.OPEN);
        assertThat(added.getTargetStage()).isEqualTo(ResearchRunStage.SYNTHESIS);

        var commentId = added.getId();
        when(reviewCommentRepository.findById(commentId)).thenReturn(Optional.of(added));
        service.resolveReviewComment(PROJECT_ID, RUN_ID, commentId, new ResolveReviewCommentCommand("addressed"));
        assertThat(added.getStatus()).isEqualTo(ReviewCommentStatus.RESOLVED);
        assertThat(added.getResolutionSummary()).isEqualTo("addressed");

        added.reopen();
        assertThat(added.getStatus()).isEqualTo(ReviewCommentStatus.OPEN);
    }

    @Test
    void resolveReviewComment_alreadyResolved_throwsConflict() {
        var run = runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var comment = new ResearchRunReviewComment(
                run, ReviewCommentTarget.RUN, "note", ReviewCommentProvenance.HUMAN_REVIEW, "author");
        var commentId = UUID.randomUUID();
        TestUtil.setField(comment, "id", commentId);
        comment.resolve("first", "actor");
        when(reviewCommentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        var cmd = new ResolveReviewCommentCommand("again");
        assertThatThrownBy(() -> service.resolveReviewComment(PROJECT_ID, RUN_ID, commentId, cmd))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addReviewComment_targetDiscriminatorMismatch_throwsValidation() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        // GATE_POINT target but no gate point supplied.
        var cmd1 = new AddReviewCommentCommand(
                ReviewCommentTarget.GATE_POINT, null, null, null, null, "body", ReviewCommentProvenance.HUMAN_REVIEW);
        assertThatThrownBy(() -> service.addReviewComment(PROJECT_ID, RUN_ID, cmd1))
                .isInstanceOf(DomainValidationException.class);
        // RUN target but a stage discriminator is set.
        var cmd2 = new AddReviewCommentCommand(
                ReviewCommentTarget.RUN,
                null,
                ResearchRunStage.SYNTHESIS,
                null,
                null,
                "body",
                ReviewCommentProvenance.HUMAN_REVIEW);
        assertThatThrownBy(() -> service.addReviewComment(PROJECT_ID, RUN_ID, cmd2))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void resolveReviewComment_crossRunComment_concealedAsNotFound() {
        runAt(ResearchRunStage.SYNTHESIS, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.COPILOT);
        var otherRun = new ResearchRun(project, "OTHER", AutonomyLevel.COPILOT);
        TestUtil.setField(otherRun, "id", UUID.randomUUID());
        var comment = new ResearchRunReviewComment(
                otherRun, ReviewCommentTarget.RUN, "note", ReviewCommentProvenance.HUMAN_REVIEW, "author");
        var commentId = UUID.randomUUID();
        TestUtil.setField(comment, "id", commentId);
        when(reviewCommentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        var cmd = new ResolveReviewCommentCommand("x");
        assertThatThrownBy(() -> service.resolveReviewComment(PROJECT_ID, RUN_ID, commentId, cmd))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------- rationale

    @Test
    void addRationaleEntry_persistsImmutableEntry() {
        runAt(ResearchRunStage.CHARTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var saved = service.addRationaleEntry(
                PROJECT_ID,
                RUN_ID,
                new AddRationaleEntryCommand(
                        ResearchRunStage.CHARTING,
                        ResearchArtifactType.CHARTING_DATA,
                        null,
                        2,
                        null,
                        RationaleEntryKind.CHARTED_VALUE,
                        RationaleEvidenceBasis.CHARTED_CELL,
                        RationaleProvenance.AGENT_RECOMMENDATION,
                        "row-12-col-outcome",
                        "value charted from full-text table 3",
                        "p7/table3",
                        "high"));
        assertThat(saved.getKind()).isEqualTo(RationaleEntryKind.CHARTED_VALUE);
        assertThat(saved.getEvidenceBasis()).isEqualTo(RationaleEvidenceBasis.CHARTED_CELL);
        assertThat(saved.getSubjectKey()).isEqualTo("row-12-col-outcome");
        assertThat(saved.getRecordedAt()).isNotNull();
    }

    @Test
    void addRationaleEntry_oversizedSubjectKey_throwsValidation() {
        runAt(ResearchRunStage.CHARTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var tooLong = "k".repeat(201);
        var cmd = new AddRationaleEntryCommand(
                ResearchRunStage.CHARTING,
                null,
                null,
                null,
                null,
                RationaleEntryKind.CHARTED_VALUE,
                RationaleEvidenceBasis.CHARTED_CELL,
                RationaleProvenance.AGENT_RECOMMENDATION,
                tooLong,
                "summary",
                null,
                null);
        assertThatThrownBy(() -> service.addRationaleEntry(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class);
    }

    // ------------------------------------------------------------ disclosure

    @Test
    void createDisclosure_withoutActiveManuscript_throwsValidation() {
        runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.empty());
        var cmd = new CreateDisclosureCommand(null, null, false, false, false);
        assertThatThrownBy(() -> service.createDisclosure(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("MANUSCRIPT");
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
        // Each read must resolve the run through the project-scoped ownership guard;
        // a refactor dropping requireRun(projectId, runId) would leak cross-project data.
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

    // ----------------------------------------------- recordArtifact -> stale

    @Test
    void recordArtifact_manuscriptRework_marksCurrentDisclosureStale() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var prior = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(prior));
        when(gateRepository.findByResearchRunIdAndGatePoint(any(), any())).thenReturn(Optional.empty());
        var current = disclosure(run, prior.getId(), true, true, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));

        service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new RecordArtifactCommand(
                        ResearchArtifactType.MANUSCRIPT, "loc-v2", null, null, null, null, null, null, null));

        assertThat(current.getStatus()).isEqualTo(DisclosureStatus.STALE);
        verify(disclosureRepository).save(current);
    }
}
