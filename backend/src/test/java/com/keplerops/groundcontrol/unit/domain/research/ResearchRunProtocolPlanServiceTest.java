package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

/**
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — service-layer unit tests for the
 * protocol plan on {@link ResearchRunService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunProtocolPlanServiceTest {

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

    private static List<SectionCommand> systematicSections() {
        return List.of(
                section("s-eligibility", ProtocolSectionKind.ELIGIBILITY_CRITERIA),
                section("s-databases", ProtocolSectionKind.DATABASES_SEARCH_STRINGS),
                section("s-screening", ProtocolSectionKind.SCREENING),
                section("s-extraction", ProtocolSectionKind.DATA_EXTRACTION),
                section("s-rob", ProtocolSectionKind.RISK_OF_BIAS_POSTURE),
                section("s-synthesis", ProtocolSectionKind.SYNTHESIS_PLAN),
                section("s-reporting", ProtocolSectionKind.REPORTING_STANDARD),
                section("s-certainty", ProtocolSectionKind.CERTAINTY_CLAIM_LIMITS),
                section("s-limits", ProtocolSectionKind.METHOD_LIMITS),
                section("s-nonclaims", ProtocolSectionKind.NON_CLAIMS));
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

    private static CoverageCommand deferred(String key) {
        return new CoverageCommand(
                key,
                ProtocolCoverageDisposition.DEFERRED_NON_BLOCKING,
                null,
                null,
                "rationale",
                ResearchRunStage.SOURCE_SEARCH,
                null);
    }

    private static List<CoverageCommand> completeCoverage() {
        return List.of(filled("req-1"), deferred("oq-1"));
    }

    // ---- record: happy path -------------------------------------------------

    @Test
    void record_persistsPlanCoverageAndSections() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), systematicSections());

        var result = service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd);

        assertThat(result.plan().getArtifactId()).isEqualTo(PROTOCOL_ARTIFACT_ID);
        assertThat(result.plan().getAttemptNo()).isEqualTo(1);
        assertThat(result.plan().getMethodKey()).isEqualTo("systematic");
        assertThat(result.coverages()).hasSize(2);
        assertThat(result.sections()).hasSize(10);
    }

    // ---- record: gating / structural validation ------------------------------

    @Test
    void record_crossProjectRun_concealedAsNotFound() {
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.empty());
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void record_noActiveProtocolPlanArtifact_throwsValidation() {
        activeRun();
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.empty());
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_artifact_missing");
    }

    @Test
    void record_planAlreadyExists_throwsConflict() {
        readyRun();
        when(protocolPlanRepository.existsByArtifactId(PROTOCOL_ARTIFACT_ID)).thenReturn(true);
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_exists");
    }

    // ---- record: coverage validation ------------------------------------------

    @Test
    void record_missingCoverageForRequirement_throwsIncomplete() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_coverage_incomplete");
    }

    @Test
    void record_unknownContractEntryKey_throwsValidation() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand(
                "1", List.of(filled("req-1"), deferred("oq-1"), filled("nope")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_unknown_contract_entry");
    }

    @Test
    void record_duplicateCoverage_throwsValidation() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand(
                "1", List.of(filled("req-1"), filled("req-1"), deferred("oq-1")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_duplicate_coverage");
    }

    @Test
    void record_coverageOnMethodLimit_throwsNotCoverable() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand(
                "1", List.of(filled("req-1"), deferred("oq-1"), filled("lim-1")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_entry_not_coverable");
    }

    @Test
    void record_coverageOnNonClaim_throwsNotCoverable() {
        readyRun();
        var cmd = new RecordProtocolPlanCommand(
                "1", List.of(filled("req-1"), deferred("oq-1"), filled("nc-1")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_entry_not_coverable");
    }

    // ---- record: per-disposition field validation ------------------------------

    @Test
    void record_filledWithoutProvenance_throwsIncomplete() {
        readyRun();
        var badFilled =
                new CoverageCommand("req-1", ProtocolCoverageDisposition.FILLED, "answer", null, null, null, null);
        var cmd = new RecordProtocolPlanCommand("1", List.of(badFilled, deferred("oq-1")), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_filled_incomplete");
    }

    @Test
    void record_deferredWithoutStage_throwsIncomplete() {
        readyRun();
        var badDeferred = new CoverageCommand(
                "oq-1", ProtocolCoverageDisposition.DEFERRED_NON_BLOCKING, null, null, "rationale", null, null);
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1"), badDeferred), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_deferred_incomplete");
    }

    @Test
    void record_blockingWithoutRationale_throwsIncomplete() {
        readyRun();
        var badBlocking = new CoverageCommand(
                "oq-1", ProtocolCoverageDisposition.BLOCKING_DECISION_REQUIRED, null, null, null, null, null);
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1"), badBlocking), systematicSections());
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_blocking_incomplete");
    }

    @Test
    void record_blockingWithRationale_accepted() {
        readyRun();
        var blocking = new CoverageCommand(
                "oq-1",
                ProtocolCoverageDisposition.BLOCKING_DECISION_REQUIRED,
                null,
                null,
                "needs a decision",
                null,
                null);
        var cmd = new RecordProtocolPlanCommand("1", List.of(filled("req-1"), blocking), systematicSections());

        var result = service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd);

        assertThat(result.coverages()).hasSize(2);
    }

    // ---- record: method-shape / section validation ------------------------------

    @Test
    void record_missingRequiredSection_throwsValidation() {
        readyRun();
        var incompleteSections = systematicSections().subList(0, 9); // drop NON_CLAIMS
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), incompleteSections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_section_missing");
    }

    @Test
    void record_duplicateSectionKey_throwsValidation() {
        readyRun();
        var sections = new ArrayList<>(systematicSections());
        sections.add(section("s-eligibility", ProtocolSectionKind.CHARTING));
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), sections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_duplicate_section_key");
    }

    @Test
    void record_sourceRoleOnNonTaxonomyMethod_throwsValidation() {
        readyRun();
        var sections = new ArrayList<>(systematicSections());
        sections.set(
                0,
                new SectionCommand(
                        "s-eligibility",
                        ProtocolSectionKind.ELIGIBILITY_CRITERIA,
                        ProtocolSourceRole.BACKGROUND_FRAMING,
                        "summary"));
        var cmd = new RecordProtocolPlanCommand("1", completeCoverage(), sections);
        assertThatThrownBy(() -> service.recordProtocolPlan(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_protocol_plan_source_role_not_allowed");
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

        // Only two of the four ADR-081 §3 taxonomy source roles are carried.
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

    /** A complete taxonomy-development section set carrying all four ADR-081 §3 source roles. */
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
