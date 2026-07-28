package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
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
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.SelectMethodologyCommand;
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

/** Split from ResearchRunMethodologyServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunMethodologyServiceRequireMethodologySourceCoverage_optionalSourceNotRead_doesNotBlockTest {
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
