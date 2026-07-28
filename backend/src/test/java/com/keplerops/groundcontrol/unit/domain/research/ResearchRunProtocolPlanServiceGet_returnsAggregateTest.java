package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntrySourceLinkRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRejectedAlternativeRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository;
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
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.SectionCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import java.util.ArrayList;
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

/** Split from ResearchRunProtocolPlanServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunProtocolPlanServiceGet_returnsAggregateTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID METHODOLOGY_ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID PROTOCOL_ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");

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
    private ProtocolPlanRepository protocolPlanRepository;

    @Mock
    private ProtocolPlanCoverageRepository protocolPlanCoverageRepository;

    @Mock
    private ProtocolPlanSectionRepository protocolPlanSectionRepository;

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
        when(protocolPlanRepository.save(any())).thenAnswer(inv -> {
            ProtocolPlan p = inv.getArgument(0);
            if (p.getId() == null) TestUtil.setField(p, "id", UUID.randomUUID());
            return p;
        });
        when(protocolPlanCoverageRepository.save(any())).thenAnswer(inv -> {
            ProtocolPlanCoverage c = inv.getArgument(0);
            if (c.getId() == null) TestUtil.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(protocolPlanSectionRepository.save(any())).thenAnswer(inv -> {
            ProtocolPlanSection s = inv.getArgument(0);
            if (s.getId() == null) TestUtil.setField(s, "id", UUID.randomUUID());
            return s;
        });
    }

    // ---- fixtures ---------------------------------------------------------

    private ResearchRun activeRun() {
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);
        TestUtil.setField(run, "currentStage", ResearchRunStage.PROTOCOL_PLANNING);
        TestUtil.setField(run, "status", ResearchRunStatus.IN_PROGRESS);
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private ResearchRunMethodologySelection selection(ResearchRun run, String methodKey) {
        var sel = new ResearchRunMethodologySelection(run, methodKey, "actor");
        TestUtil.setField(sel, "id", SELECTION_ID);
        return sel;
    }

    private ResearchRunArtifact methodologyArtifact(ResearchRun run) {
        var a = new ResearchRunArtifact(run, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, 1);
        TestUtil.setField(a, "id", METHODOLOGY_ARTIFACT_ID);
        return a;
    }

    private ResearchRunArtifact protocolArtifact(ResearchRun run) {
        var a = new ResearchRunArtifact(run, ResearchArtifactType.PROTOCOL_PLAN, 1);
        TestUtil.setField(a, "id", PROTOCOL_ARTIFACT_ID);
        return a;
    }

    private MethodologyRequirementsContractEntry entry(
            MethodologyRequirementsContract contract, ContractEntryKind kind, String key) {
        var e = new MethodologyRequirementsContractEntry(contract, kind, key, "statement " + key, null, "actor");
        TestUtil.setField(e, "id", UUID.randomUUID());
        return e;
    }

    private static SectionCommand section(String key, ProtocolSectionKind kind) {
        return new SectionCommand(key, kind, null, "summary for " + key);
    }

    /** A run whose protocol/methodology artifacts + contract + selection are all in place, method = systematic. */
    private ResearchRun readyRun() {
        var run = activeRun();
        var sel = selection(run, "systematic");
        var mArtifact = methodologyArtifact(run);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var entries = new ArrayList<MethodologyRequirementsContractEntry>();
        entries.add(entry(contract, ContractEntryKind.REQUIREMENT, "req-1"));
        entries.add(entry(contract, ContractEntryKind.OPEN_PROTOCOL_QUESTION, "oq-1"));
        entries.add(entry(contract, ContractEntryKind.METHOD_LIMIT, "lim-1"));
        entries.add(entry(contract, ContractEntryKind.NON_CLAIM, "nc-1"));

        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(protocolArtifact(run)));
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(mArtifact));
        when(contractRepository.findByArtifactId(METHODOLOGY_ARTIFACT_ID)).thenReturn(Optional.of(contract));
        when(contractEntryRepository.findByContractIdOrderByCreatedAtAsc(CONTRACT_ID))
                .thenReturn(entries);
        when(selectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(RUN_ID))
                .thenReturn(Optional.of(sel));
        when(protocolPlanRepository.existsByArtifactId(PROTOCOL_ARTIFACT_ID)).thenReturn(false);
        return run;
    }

    @Test
    void get_returnsAggregate() {
        var run = readyRun();
        var sel = selection(run, "systematic");
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var plan = new ProtocolPlan(run, contract, PROTOCOL_ARTIFACT_ID, 1, "1", "systematic", "1", "actor");
        TestUtil.setField(plan, "id", UUID.randomUUID());
        var coverage = new ProtocolPlanCoverage(
                plan,
                "req-1",
                ProtocolCoverageDisposition.FILLED,
                "answer",
                ProtocolAnswerProvenance.METHODOLOGY_SOURCE,
                null,
                null,
                null,
                "actor");
        var section = new ProtocolPlanSection(
                plan, "sec-1", ProtocolSectionKind.ELIGIBILITY_CRITERIA, null, "content", "actor");
        when(protocolPlanRepository.findByArtifactId(PROTOCOL_ARTIFACT_ID)).thenReturn(Optional.of(plan));
        when(protocolPlanCoverageRepository.findByProtocolPlanId(plan.getId())).thenReturn(List.of(coverage));
        when(protocolPlanSectionRepository.findByProtocolPlanId(plan.getId())).thenReturn(List.of(section));

        var result = service.getProtocolPlan(PROJECT_ID, RUN_ID);

        // Assert the child collections flow through from the repositories (not a hollow shell),
        // and verify() the queries actually happened so a dropped child query cannot pass silently.
        assertThat(result.plan()).isSameAs(plan);
        assertThat(result.coverages()).containsExactly(coverage);
        assertThat(result.sections()).containsExactly(section);
        verify(protocolPlanCoverageRepository).findByProtocolPlanId(plan.getId());
        verify(protocolPlanSectionRepository).findByProtocolPlanId(plan.getId());
    }
}
