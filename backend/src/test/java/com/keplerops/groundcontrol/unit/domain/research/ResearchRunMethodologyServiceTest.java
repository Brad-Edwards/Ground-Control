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
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
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
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologySourceCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.SelectMethodologyCommand;
import com.keplerops.groundcontrol.domain.research.service.UpdateMethodologySourceStateCommand;
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
 * GC-RSCH-F006 — service-layer unit tests for methodology selection and
 * source coverage gate on {@link ResearchRunService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunMethodologyServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

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

    // Real catalog (loads classpath:research/methodology-catalog.yaml) so
    // selectMethodology derives label/versions/required-sources from real data.
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
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository
                                .class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntryRepository.class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntrySourceLinkRepository.class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractRejectedAlternativeRepository.class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository.class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository.class),
                org.mockito.Mockito.mock(
                        com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository.class));
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        when(selectionRepository.save(any())).thenAnswer(inv -> {
            ResearchRunMethodologySelection s = inv.getArgument(0);
            if (s.getId() == null) TestUtil.setField(s, "id", SELECTION_ID);
            return s;
        });
        when(sourceRepository.save(any())).thenAnswer(inv -> {
            ResearchRunMethodologySource s = inv.getArgument(0);
            if (s.getId() == null) TestUtil.setField(s, "id", SOURCE_ID);
            return s;
        });
    }

    private ResearchRun activeRun() {
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        TestUtil.setField(run, "currentStage", ResearchRunStage.METHODOLOGY_SELECTION);
        TestUtil.setField(run, "status", ResearchRunStatus.IN_PROGRESS);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private ResearchRun stoppedRun() {
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        TestUtil.setField(run, "currentStage", ResearchRunStage.METHODOLOGY_SELECTION);
        TestUtil.setField(run, "status", ResearchRunStatus.STOPPED);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private ResearchRunMethodologySelection selection(ResearchRun run) {
        var sel = new ResearchRunMethodologySelection(run, "systematic", "actor");
        // Mirror what the service derives from the catalog "systematic" profile so
        // re-selecting the same method is recognized as idempotent.
        sel.setMethodLabel("Systematic review");
        sel.setProfileVersion("1");
        sel.setCatalogVersion("1");
        TestUtil.setField(sel, "id", SELECTION_ID);
        return sel;
    }

    private ResearchRunMethodologySource requiredSource(
            ResearchRunMethodologySelection sel, MethodologySourceState state) {
        var src = new ResearchRunMethodologySource(sel, "doi:10.1234/example", true, "actor");
        TestUtil.setField(src, "id", SOURCE_ID);
        TestUtil.setField(src, "state", state);
        return src;
    }

    private ResearchRunMethodologySource optionalSource(
            ResearchRunMethodologySelection sel, MethodologySourceState state) {
        var src = new ResearchRunMethodologySource(sel, "doi:10.1234/optional", false, "actor");
        TestUtil.setField(src, "id", UUID.randomUUID());
        TestUtil.setField(src, "state", state);
        return src;
    }

    // -----------------------------------------------------------------------
    // selectMethodology
    // -----------------------------------------------------------------------

    @Test
    void selectMethodology_createsNewSelection_derivesLabelAndVersions() {
        activeRun();
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.empty());

        var result = service.selectMethodology(PROJECT_ID, RUN_ID, new SelectMethodologyCommand("systematic"));

        // Label and versions are DERIVED from the catalog, not supplied by the caller.
        assertThat(result.getMethodKey()).isEqualTo("systematic");
        assertThat(result.getMethodLabel()).isEqualTo("Systematic review");
        assertThat(result.getProfileVersion()).isEqualTo("1");
        assertThat(result.getCatalogVersion()).isEqualTo("1");
        verify(selectionRepository).save(any());
    }

    @Test
    void selectMethodology_snapshotsCatalogRequiredSources() {
        activeRun();
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.empty());

        service.selectMethodology(PROJECT_ID, RUN_ID, new SelectMethodologyCommand("systematic"));

        // The catalog "systematic" profile declares two required sources; both are
        // snapshotted as required=true rows in ATTEMPTED state.
        var captor = ArgumentCaptor.forClass(ResearchRunMethodologySource.class);
        verify(sourceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(s -> {
                    assertThat(s.isRequired()).isTrue();
                    assertThat(s.getState()).isEqualTo(MethodologySourceState.ATTEMPTED);
                })
                .extracting(ResearchRunMethodologySource::getSourceRef)
                .containsExactlyInAnyOrder("FRM9HPNG", "MJX3HCT5");
    }

    @Test
    void selectMethodology_unknownMethod_throwsValidation() {
        // An unknown method key is rejected against the backend catalog (ADR-078).
        activeRun();
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.empty());

        var cmd = new SelectMethodologyCommand("not-a-method");
        assertThatThrownBy(() -> service.selectMethodology(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_methodology_unknown_method");
    }

    @Test
    void selectMethodology_reselectSameMethod_idempotent() {
        var run = activeRun();
        var existing = selection(run);
        // Existing required sources match the catalog "systematic" profile snapshot.
        var s1 = new ResearchRunMethodologySource(existing, "FRM9HPNG", true, "actor");
        TestUtil.setField(s1, "id", UUID.randomUUID());
        var s2 = new ResearchRunMethodologySource(existing, "MJX3HCT5", true, "actor");
        TestUtil.setField(s2, "id", UUID.randomUUID());
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(existing));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(s1, s2));

        var result = service.selectMethodology(PROJECT_ID, RUN_ID, new SelectMethodologyCommand("systematic"));

        assertThat(result).isSameAs(existing);
        // Must NOT supersede or create a new selection.
        verify(selectionRepository, never()).save(existing);
    }

    @Test
    void selectMethodology_differentKey_supersedesAndReopens() {
        var run = activeRun();
        var existing = selection(run);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(existing));
        // The idempotency check is only reached when the method matches; the key
        // differs ("scoping" vs "systematic"), so this is a no-op for the supersede
        // path but satisfies lenient Mockito setup.
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of());

        service.selectMethodology(PROJECT_ID, RUN_ID, new SelectMethodologyCommand("scoping"));

        // Existing selection must be superseded.
        assertThat(existing.getSupersededAt()).isNotNull();
        verify(selectionRepository).save(existing);
        ArgumentCaptor<ResearchRunMethodologySelection> captor =
                ArgumentCaptor.forClass(ResearchRunMethodologySelection.class);
        verify(selectionRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        var newSel = captor.getAllValues().stream()
                .filter(s -> s.getMethodKey().equals("scoping"))
                .findFirst();
        assertThat(newSel).isPresent();
        // The new selection re-snapshots the catalog "scoping" profile's 3 required sources.
        verify(sourceRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void selectMethodology_afterRequirementsRecorded_rejectsReselection() {
        var run = activeRun();
        var existing = selection(run);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(existing));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of());
        // An accepted METHODOLOGY_REQUIREMENTS artifact already depends on the current
        // selection; reselecting a different method would leave its coverage unsatisfied.
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(
                        Optional.of(new ResearchRunArtifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, 1)));

        var cmd = new SelectMethodologyCommand("scoping");
        assertThatThrownBy(() -> service.selectMethodology(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_methodology_locked_after_requirements");
        // The existing selection must NOT be superseded.
        assertThat(existing.getSupersededAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // recordMethodologySource
    // -----------------------------------------------------------------------

    @Test
    void recordMethodologySource_addsToActiveSelection_asOptional() {
        var run = activeRun();
        var sel = selection(run);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionIdAndSourceRef(SELECTION_ID, "doi:10.1234/paper"))
                .thenReturn(Optional.empty());

        var result = service.recordMethodologySource(
                PROJECT_ID, RUN_ID, new RecordMethodologySourceCommand(null, "doi:10.1234/paper", "A Paper"));

        assertThat(result.getSourceRef()).isEqualTo("doi:10.1234/paper");
        assertThat(result.getState()).isEqualTo(MethodologySourceState.ATTEMPTED);
        // recordMethodologySource always creates optional sources
        assertThat(result.isRequired()).isFalse();
    }

    @Test
    void recordMethodologySource_idempotentOnSourceRef() {
        var run = activeRun();
        var sel = selection(run);
        var existing = requiredSource(sel, MethodologySourceState.OBTAINED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionIdAndSourceRef(SELECTION_ID, "doi:10.1234/example"))
                .thenReturn(Optional.of(existing));

        var result = service.recordMethodologySource(
                PROJECT_ID, RUN_ID, new RecordMethodologySourceCommand(null, "doi:10.1234/example", null));

        assertThat(result).isSameAs(existing);
        verify(sourceRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // updateMethodologySourceState
    // -----------------------------------------------------------------------

    @Test
    void updateMethodologySourceState_validTransition_updatesState() {
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.OBTAINED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var result = service.updateMethodologySourceState(
                PROJECT_ID, RUN_ID, SOURCE_ID, new UpdateMethodologySourceStateCommand(MethodologySourceState.READ));

        assertThat(result.getState()).isEqualTo(MethodologySourceState.READ);
        verify(sourceRepository).save(src);
    }

    @Test
    void updateMethodologySourceState_illegalJump_attemptedToRead_throwsConflict() {
        // ATTEMPTED → READ is not a valid transition (must go ATTEMPTED→OBTAINED→READ).
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.ATTEMPTED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var cmd = new UpdateMethodologySourceStateCommand(MethodologySourceState.READ);
        assertThatThrownBy(() -> service.updateMethodologySourceState(PROJECT_ID, RUN_ID, SOURCE_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_methodology_source_invalid_transition");
    }

    @Test
    void updateMethodologySourceState_illegalJump_blockedToRead_throwsConflict() {
        // BLOCKED → READ is not a valid transition (must re-attempt first).
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.BLOCKED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var cmd = new UpdateMethodologySourceStateCommand(MethodologySourceState.READ);
        assertThatThrownBy(() -> service.updateMethodologySourceState(PROJECT_ID, RUN_ID, SOURCE_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_methodology_source_invalid_transition");
    }

    @Test
    void updateMethodologySourceState_stoppedRun_throwsConflict() {
        // Mutation of source state requires an active run.
        stoppedRun();

        var cmd = new UpdateMethodologySourceStateCommand(MethodologySourceState.OBTAINED);
        assertThatThrownBy(() -> service.updateMethodologySourceState(PROJECT_ID, RUN_ID, SOURCE_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_not_active");
    }

    // -----------------------------------------------------------------------
    // requireMethodologySourceCoverageComplete (via recordArtifact)
    // -----------------------------------------------------------------------

    @Test
    void requireMethodologySourceCoverage_noSelection_throwsValidation() {
        activeRun();
        // The run is at METHODOLOGY_SELECTION stage
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.empty());

        var cmd = new RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordArtifact(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("methodology selection")
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_methodology_selection_missing");
    }

    @Test
    void requireMethodologySourceCoverage_requiredSourceNotRead_throwsValidation() {
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.OBTAINED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var cmd = new RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordArtifact(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_methodology_sources_incomplete");
    }

    @Test
    void requireMethodologySourceCoverage_requiredRefSnapshotted_gateBLocks() {
        // Proves the vacuous-pass hole is closed: a required source is snapshotted at
        // selection in ATTEMPTED state; leaving it untransitioned blocks the coverage gate.
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.ATTEMPTED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var cmd = new RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordArtifact(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_methodology_sources_incomplete");
    }

    @Test
    void requireMethodologySourceCoverage_optionalSourceNotRead_doesNotBlock() {
        var run = activeRun();
        var sel = selection(run);
        // Required source is READ, optional source is OBTAINED — gate should pass
        var required = requiredSource(sel, MethodologySourceState.READ);
        var optional = optionalSource(sel, MethodologySourceState.OBTAINED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(required, optional));
        // Stub artifact save to succeed
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any())).thenAnswer(inv -> {
            var a = inv.getArgument(0);
            TestUtil.setField(a, "id", UUID.randomUUID());
            return a;
        });
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw
        var result = service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new RecordArtifactCommand(
                        ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null));

        assertThat(result).isNotNull();
    }

    @Test
    void requireMethodologySourceCoverage_requiredSourceBlocked_throwsConflict() {
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.BLOCKED);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));

        var cmd = new RecordArtifactCommand(
                ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordArtifact(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_methodology_source_blocked");
    }

    @Test
    void requireMethodologySourceCoverage_allRequiredRead_passes() {
        var run = activeRun();
        var sel = selection(run);
        var src = requiredSource(sel, MethodologySourceState.READ);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID)).thenReturn(List.of(src));
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any())).thenAnswer(inv -> {
            var a = inv.getArgument(0);
            TestUtil.setField(a, "id", UUID.randomUUID());
            return a;
        });
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new RecordArtifactCommand(
                        ResearchArtifactType.METHODOLOGY_REQUIREMENTS, null, null, null, null, null, null, null, null));

        assertThat(result).isNotNull();
    }

    @Test
    void crossProjectMiss_throwsNotFound() {
        var otherProjectId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(runRepository.findByIdAndProjectId(RUN_ID, otherProjectId)).thenReturn(Optional.empty());

        var cmd = new SelectMethodologyCommand("systematic");
        assertThatThrownBy(() -> service.selectMethodology(otherProjectId, RUN_ID, cmd))
                .isInstanceOf(NotFoundException.class);
    }
}
