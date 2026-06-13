package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceService;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkType;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ControlWorkspaceServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID TEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID ASSESSMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FINDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID SCI_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final Instant AS_OF = Instant.parse("2026-06-01T12:00:00Z");

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ScopedControlImplementationRepository scopedControlImplementationRepository;

    @Mock
    private ControlTestRepository controlTestRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository assessmentRepository;

    @Mock
    private EvidenceArtifactRepository evidenceArtifactRepository;

    @Mock
    private FindingLinkRepository findingLinkRepository;

    @Mock
    private RiskControlMappingRepository riskControlMappingRepository;

    @InjectMocks
    private ControlWorkspaceService service;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    @Test
    void workspaceComposesCatalogAssuranceExceptionsAndOwnerQueue() {
        var control = control("CTL-001", "Payment approval", null, ControlStatus.DRAFT);
        var asset = new OperationalAsset(project, "ASSET-001", "Payments service");
        asset.setAssetType(AssetType.SERVICE);
        setField(asset, "id", ASSET_ID);
        var scoped = new ScopedControlImplementation(project, "SCI-001", control, "Payments deployment");
        scoped.setImplementationScope("Payments production only");
        scoped.setOperationalAsset(asset);
        setField(scoped, "id", SCI_ID);

        var test = new ControlTest(
                project,
                control,
                "CTEST-001",
                ControlTestMethodology.INSPECTION,
                ControlTestConclusion.INEFFECTIVE,
                "auditor",
                LocalDate.parse("2026-05-31"));
        test.setTestSteps("Review approvals");
        test.setExpectedResults("Approvals are present");
        test.setActualResults("Missing approvals");
        setField(test, "id", TEST_ID);
        var assessment = new ControlEffectivenessAssessment(
                project,
                control,
                "CEA-001",
                ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                ControlEffectivenessRating.INEFFECTIVE,
                LocalDate.parse("2026-05-31"),
                "assessor");
        assessment.setSupportingTestIds(List.of(TEST_ID.toString()));
        setField(assessment, "id", ASSESSMENT_ID);

        var artifact = new EvidenceArtifact(
                project,
                "EV-001",
                "Approval test evidence",
                "Control test evidence summary",
                EvidenceType.CONTROL_TEST_SUMMARY,
                "assurance-agent",
                AS_OF.minusSeconds(60));
        artifact.setSources(List.of(
                new EvidenceSourceRef(EvidenceSourceKind.CONTROL_TEST, TEST_ID, null, "test"),
                new EvidenceSourceRef(
                        EvidenceSourceKind.CONTROL_EFFECTIVENESS_ASSESSMENT, ASSESSMENT_ID, null, "assessment")));
        setField(artifact, "id", ARTIFACT_ID);

        var finding = new Finding(
                project,
                "FIND-001",
                "Approval exception",
                FindingType.CONTROL_DEFICIENCY,
                FindingSeverity.HIGH,
                "Approvals missing");
        setField(finding, "id", FINDING_ID);
        var findingLink =
                new FindingLink(finding, FindingLinkTargetType.CONTROL, CONTROL_ID, null, FindingLinkType.AFFECTS);

        var scenario = new RiskScenario(project, "RS-001", "Approval bypass", "Insider", "Bypass", "Payments", "Loss");
        setField(scenario, "id", UUID.fromString("00000000-0000-0000-0000-000000000901"));
        var mapping = RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
        mapping.setMappingObjective("Prevent unapproved payments");
        mapping.addEvidenceRef(new MappingEvidenceRef("EVD-REF-001", "Approval packet", ARTIFACT_ID));
        setField(mapping, "id", MAPPING_ID);

        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));
        when(scopedControlImplementationRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(scoped));
        when(controlTestRepository.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(
                        PROJECT_ID, LocalDate.parse("2026-06-01")))
                .thenReturn(List.of(test));
        when(assessmentRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        PROJECT_ID, LocalDate.parse("2026-06-01")))
                .thenReturn(List.of(assessment));
        when(evidenceArtifactRepository.findByProjectIdAndDerivedAtLessThanEqualOrderByDerivedAtDesc(PROJECT_ID, AS_OF))
                .thenReturn(List.of(artifact));
        when(findingLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(findingLink));
        when(riskControlMappingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(mapping));

        var result = service.workspace(PROJECT_ID, AS_OF, 30, null, null, null, null);

        assertThat(result.controlCount()).isEqualTo(1);
        var item = result.controls().getFirst();
        assertThat(item.uid()).isEqualTo("CTL-001");
        assertThat(item.scopedImplementations()).extracting("uid").containsExactly("SCI-001");
        assertThat(item.tests()).extracting("uid").containsExactly("CTEST-001");
        assertThat(item.assessments()).extracting("uid").containsExactly("CEA-001");
        assertThat(item.evidence()).extracting("uid").containsExactly("EV-001");
        assertThat(item.findings()).extracting("uid").containsExactly("FIND-001");
        assertThat(item.riskMappings()).extracting("targetIdentifier").containsExactly("RS-001");
        assertThat(item.riskMappings().getFirst().evidenceRefs())
                .extracting("evidenceRef")
                .containsExactly("EVD-REF-001");
        assertThat(item.queueReasons())
                .contains("OWNER_MISSING", "STATUS_DRAFT", "OPEN_EXCEPTION", "EFFECTIVENESS_WEAK");
    }

    @Test
    void workspaceFiltersByOwnerStatusFunctionAndQueueReason() {
        var current = control("CTL-001", "MFA", "Alice", ControlStatus.OPERATIONAL);
        var draft = control("CTL-002", "Logging", "Bob", ControlStatus.DRAFT);

        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(current, draft));
        when(scopedControlImplementationRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(controlTestRepository.findByProjectIdOrderByTestDateDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(assessmentRepository.findByProjectIdOrderByAssessedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(findingLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(riskControlMappingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());

        var result = service.workspace(
                PROJECT_ID,
                null,
                90,
                ControlStatus.OPERATIONAL,
                ControlFunction.PREVENTIVE,
                "ali",
                "ASSESSMENT_MISSING");

        assertThat(result.controls()).extracting("uid").containsExactly("CTL-001");
    }

    @Test
    void workspaceRejectsNonPositiveFreshnessWindow() {
        assertThatThrownBy(() -> service.workspace(PROJECT_ID, null, 0, null, null, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("freshnessWindowDays");
    }

    private Control control(String uid, String title, String owner, ControlStatus status) {
        var control = new Control(project, uid, title, ControlFunction.PREVENTIVE);
        control.setOwner(owner);
        setField(control, "status", status);
        setField(control, "id", uid.equals("CTL-001") ? CONTROL_ID : UUID.randomUUID());
        return control;
    }
}
