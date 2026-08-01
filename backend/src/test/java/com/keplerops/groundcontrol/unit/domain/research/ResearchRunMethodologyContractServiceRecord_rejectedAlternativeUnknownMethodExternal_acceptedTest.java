package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractRejectedAlternative;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntrySourceLinkRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRejectedAlternativeRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository;
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
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.EntryCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.SourceLinkCommand;
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

/** Split from ResearchRunMethodologyContractServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunMethodologyContractServiceRecord_rejectedAlternativeUnknownMethodExternal_acceptedTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID READ_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID OPTIONAL_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000031");

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

    @Mock
    private MethodologyRequirementsContractRepository contractRepository;

    @Mock
    private MethodologyRequirementsContractEntryRepository contractEntryRepository;

    @Mock
    private MethodologyRequirementsContractEntrySourceLinkRepository contractEntrySourceLinkRepository;

    @Mock
    private MethodologyRequirementsContractRejectedAlternativeRepository contractRejectedAlternativeRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository protocolPlanRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository
            protocolPlanCoverageRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository
            protocolPlanSectionRepository;

    private final MethodologyCatalog methodologyCatalog = new MethodologyCatalog();
    private ResearchRunService service;
    private Project project;

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
                contractRepository,
                contractEntryRepository,
                contractEntrySourceLinkRepository,
                contractRejectedAlternativeRepository,
                protocolPlanRepository,
                protocolPlanCoverageRepository,
                protocolPlanSectionRepository);
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        when(contractRepository.save(any())).thenAnswer(inv -> {
            MethodologyRequirementsContract c = inv.getArgument(0);
            if (c.getId() == null) TestUtil.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(contractEntryRepository.save(any())).thenAnswer(inv -> {
            MethodologyRequirementsContractEntry e = inv.getArgument(0);
            if (e.getId() == null) TestUtil.setField(e, "id", UUID.randomUUID());
            return e;
        });
        when(contractEntrySourceLinkRepository.save(any())).thenAnswer(inv -> {
            MethodologyRequirementsContractEntrySourceLink l = inv.getArgument(0);
            if (l.getId() == null) TestUtil.setField(l, "id", UUID.randomUUID());
            return l;
        });
        when(contractRejectedAlternativeRepository.save(any())).thenAnswer(inv -> {
            MethodologyRequirementsContractRejectedAlternative r = inv.getArgument(0);
            if (r.getId() == null) TestUtil.setField(r, "id", UUID.randomUUID());
            return r;
        });
    }

    // ---- fixtures ---------------------------------------------------------

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
        TestUtil.setField(sel, "id", SELECTION_ID);
        return sel;
    }

    private ResearchRunArtifact activeArtifact(ResearchRun run) {
        var a = new ResearchRunArtifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, 1);
        TestUtil.setField(a, "id", ARTIFACT_ID);
        return a;
    }

    private ResearchRunMethodologySource source(
            ResearchRunMethodologySelection sel, UUID id, String ref, boolean required, MethodologySourceState state) {
        var src = new ResearchRunMethodologySource(sel, ref, required, "actor");
        TestUtil.setField(src, "id", id);
        TestUtil.setField(src, "state", state);
        return src;
    }

    /** A run whose artifact + selection + coverage are all satisfied, with one READ required source. */
    private ResearchRun readyRun() {
        var run = activeRun();
        var sel = selection(run);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(activeArtifact(run)));
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(sourceRepository.findBySelectionId(SELECTION_ID))
                .thenReturn(List.of(
                        source(sel, READ_SOURCE_ID, "doi:read", true, MethodologySourceState.READ),
                        source(sel, OPTIONAL_SOURCE_ID, "doi:optional", false, MethodologySourceState.OBTAINED)));
        when(contractRepository.existsByArtifactId(ARTIFACT_ID)).thenReturn(false);
        return run;
    }

    private static EntryCommand requirement(String key, UUID sourceId) {
        return new EntryCommand(
                ContractEntryKind.REQUIREMENT,
                key,
                "statement " + key,
                List.of(new SourceLinkCommand(sourceId, "p.1")),
                null);
    }

    @Test
    void record_rejectedAlternativeUnknownMethodExternal_accepted() {
        readyRun();
        var cmd = new RecordMethodologyRequirementsContractCommand(
                List.of(requirement("r", READ_SOURCE_ID)),
                List.of(new RejectedAlternativeCommand("some-external-method", "1", null, true)));

        var result = service.recordMethodologyRequirementsContract(PROJECT_ID, RUN_ID, cmd);

        assertThat(result.rejectedAlternatives()).hasSize(1);
        verify(contractRejectedAlternativeRepository).save(any());
    }

    // ---- get --------------------------------------------------------------

    @Test
    void get_noContract_throwsNotFound() {
        var run = activeRun();
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(activeArtifact(run)));
        when(contractRepository.findByArtifactId(ARTIFACT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMethodologyRequirementsContract(PROJECT_ID, RUN_ID))
                .isInstanceOf(NotFoundException.class);
        verify(contractRepository, never()).save(any());
    }

    @Test
    void get_returnsAggregate() {
        var run = readyRun();
        var sel = selection(run);
        var contract = new MethodologyRequirementsContract(run, sel, ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", UUID.randomUUID());
        when(contractRepository.findByArtifactId(ARTIFACT_ID)).thenReturn(Optional.of(contract));
        when(contractEntryRepository.findByContractIdOrderByCreatedAtAsc(contract.getId()))
                .thenReturn(List.of());
        when(contractEntrySourceLinkRepository.findByEntryContractIdOrderByCreatedAtAsc(contract.getId()))
                .thenReturn(List.of());
        when(contractRejectedAlternativeRepository.findByContractIdOrderByCreatedAtAsc(contract.getId()))
                .thenReturn(List.of());

        var result = service.getMethodologyRequirementsContract(PROJECT_ID, RUN_ID);

        assertThat(result.contract()).isSameAs(contract);
        // Pin the aggregate assembly: every child collection is populated from its
        // own repository query. isEmpty() catches a null-field regression; the
        // verify() calls catch a dropped child query that would return a hollow
        // contract shell to callers (including the MCP surface).
        assertThat(result.entries()).isEmpty();
        assertThat(result.sourceLinks()).isEmpty();
        assertThat(result.rejectedAlternatives()).isEmpty();
        verify(contractEntryRepository).findByContractIdOrderByCreatedAtAsc(contract.getId());
        verify(contractEntrySourceLinkRepository).findByEntryContractIdOrderByCreatedAtAsc(contract.getId());
        verify(contractRejectedAlternativeRepository).findByContractIdOrderByCreatedAtAsc(contract.getId());
    }
}
