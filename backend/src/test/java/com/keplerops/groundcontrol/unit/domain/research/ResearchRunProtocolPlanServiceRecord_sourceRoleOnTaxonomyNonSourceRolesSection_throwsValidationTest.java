package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
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
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
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
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.CoverageCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.SectionCommand;
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

/** Split from ResearchRunProtocolPlanServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunProtocolPlanServiceRecord_sourceRoleOnTaxonomyNonSourceRolesSection_throwsValidationTest {
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

    private static CoverageCommand filled(String key) {
        return new CoverageCommand(
                key,
                ProtocolCoverageDisposition.FILLED,
                "answer",
                ProtocolAnswerProvenance.METHODOLOGY_SOURCE,
                null,
                null,
                null);
    }

    /** A complete taxonomy-development section set carrying all four ADR-083 §3 source roles. */
    private static List<SectionCommand> taxonomySectionsAllRoles() {
        return List.of(
                section("meta", ProtocolSectionKind.META_CHARACTERISTIC),
                section("unit", ProtocolSectionKind.UNIT_OF_ANALYSIS),
                new SectionCommand(
                        "roles-instance",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.TAXONOMY_INSTANCE_CORPUS,
                        "instances"),
                new SectionCommand(
                        "roles-background",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.BACKGROUND_FRAMING,
                        "background"),
                new SectionCommand(
                        "roles-methodology",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.METHODOLOGY_LITERATURE,
                        "methodology"),
                new SectionCommand(
                        "roles-validation",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.VALIDATION_EVALUATION,
                        "validation"),
                section("start", ProtocolSectionKind.STARTING_CONCEPTS),
                section("construct", ProtocolSectionKind.CONSTRUCTION_PROCEDURE),
                section("iter", ProtocolSectionKind.ITERATION_LOG_PROTOCOL),
                section("end", ProtocolSectionKind.ENDING_CONDITIONS),
                section("eval", ProtocolSectionKind.EVALUATION_PLAN),
                section("threats", ProtocolSectionKind.VALIDITY_THREATS),
                section("limits", ProtocolSectionKind.METHOD_LIMITS),
                section("nonclaims", ProtocolSectionKind.NON_CLAIMS));
    }

    @Test
    void record_sourceRoleOnTaxonomyNonSourceRolesSection_throwsValidation() {
        var run = activeRun();
        var sel = selection(run, "taxonomy_development");
        var mArtifact = methodologyArtifact(run);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var entries = List.of(entry(contract, ContractEntryKind.REQUIREMENT, "req-1"));
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

        var taxonomySections = List.of(
                new SectionCommand(
                        "meta", ProtocolSectionKind.META_CHARACTERISTIC, ProtocolSourceRole.BACKGROUND_FRAMING, "s"),
                section("unit", ProtocolSectionKind.UNIT_OF_ANALYSIS),
                section("roles", ProtocolSectionKind.SOURCE_ROLES),
                section("start", ProtocolSectionKind.STARTING_CONCEPTS),
                section("construct", ProtocolSectionKind.CONSTRUCTION_PROCEDURE),
                section("iter", ProtocolSectionKind.ITERATION_LOG_PROTOCOL),
                section("end", ProtocolSectionKind.ENDING_CONDITIONS),
                section("eval", ProtocolSectionKind.EVALUATION_PLAN),
                section("threats", ProtocolSectionKind.VALIDITY_THREATS),
                section("limits", ProtocolSectionKind.METHOD_LIMITS),
                section("nonclaims", ProtocolSectionKind.NON_CLAIMS));
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1")), taxonomySections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_source_role_not_allowed");
    }

    @Test
    void record_sourceRoleOnTaxonomySourceRolesSection_accepted() {
        var run = activeRun();
        var sel = selection(run, "taxonomy_development");
        var mArtifact = methodologyArtifact(run);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var entries = List.of(entry(contract, ContractEntryKind.REQUIREMENT, "req-1"));
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

        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1")), taxonomySectionsAllRoles());

        var result = service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd);

        assertThat(result.sections()).hasSize(14);
    }

    @Test
    void record_taxonomyMissingSourceRole_throwsValidation() {
        var run = activeRun();
        var sel = selection(run, "taxonomy_development");
        var mArtifact = methodologyArtifact(run);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var entries = List.of(entry(contract, ContractEntryKind.REQUIREMENT, "req-1"));
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

        // Only two of the four ADR-083 §3 taxonomy source roles are carried.
        var taxonomySections = List.of(
                section("meta", ProtocolSectionKind.META_CHARACTERISTIC),
                section("unit", ProtocolSectionKind.UNIT_OF_ANALYSIS),
                new SectionCommand(
                        "roles-instance",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.TAXONOMY_INSTANCE_CORPUS,
                        "instances"),
                new SectionCommand(
                        "roles-background",
                        ProtocolSectionKind.SOURCE_ROLES,
                        ProtocolSourceRole.BACKGROUND_FRAMING,
                        "background"),
                section("start", ProtocolSectionKind.STARTING_CONCEPTS),
                section("construct", ProtocolSectionKind.CONSTRUCTION_PROCEDURE),
                section("iter", ProtocolSectionKind.ITERATION_LOG_PROTOCOL),
                section("end", ProtocolSectionKind.ENDING_CONDITIONS),
                section("eval", ProtocolSectionKind.EVALUATION_PLAN),
                section("threats", ProtocolSectionKind.VALIDITY_THREATS),
                section("limits", ProtocolSectionKind.METHOD_LIMITS),
                section("nonclaims", ProtocolSectionKind.NON_CLAIMS));
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1")), taxonomySections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_source_roles_incomplete");
    }

    @Test
    void record_taxonomySourceRolesSectionWithoutRole_throwsValidation() {
        var run = activeRun();
        var sel = selection(run, "taxonomy_development");
        var mArtifact = methodologyArtifact(run);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        TestUtil.setField(contract, "id", CONTRACT_ID);
        var entries = List.of(entry(contract, ContractEntryKind.REQUIREMENT, "req-1"));
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

        // A SOURCE_ROLES section that names no role would let the roles collapse.
        var taxonomySections = List.of(
                section("meta", ProtocolSectionKind.META_CHARACTERISTIC),
                section("unit", ProtocolSectionKind.UNIT_OF_ANALYSIS),
                section("roles", ProtocolSectionKind.SOURCE_ROLES),
                section("start", ProtocolSectionKind.STARTING_CONCEPTS),
                section("construct", ProtocolSectionKind.CONSTRUCTION_PROCEDURE),
                section("iter", ProtocolSectionKind.ITERATION_LOG_PROTOCOL),
                section("end", ProtocolSectionKind.ENDING_CONDITIONS),
                section("eval", ProtocolSectionKind.EVALUATION_PLAN),
                section("threats", ProtocolSectionKind.VALIDITY_THREATS),
                section("limits", ProtocolSectionKind.METHOD_LIMITS),
                section("nonclaims", ProtocolSectionKind.NON_CLAIMS));
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1")), taxonomySections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_source_role_required");
    }

    // ---- get --------------------------------------------------------------

    @Test
    void get_noPlan_throwsNotFound() {
        var run = activeRun();
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(protocolArtifact(run)));
        when(protocolPlanRepository.findByArtifactId(PROTOCOL_ARTIFACT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProtocolPlan(PROJECT_ID, RUN_ID)).isInstanceOf(NotFoundException.class);
    }
}
