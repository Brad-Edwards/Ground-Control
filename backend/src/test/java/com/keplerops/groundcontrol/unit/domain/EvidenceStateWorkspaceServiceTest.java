package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceService;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkType;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceStateWorkspaceServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OBSERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID ASSESSMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID FINDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final Instant AS_OF = Instant.parse("2026-06-01T12:00:00Z");

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @Mock
    private EvidenceArtifactRepository evidenceArtifactRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private ControlTestRepository controlTestRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private FindingLinkRepository findingLinkRepository;

    @InjectMocks
    private EvidenceStateWorkspaceService service;

    @Test
    void workspaceComposesFreshnessProvenanceAndImpact() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var asset = new OperationalAsset(project, "ASSET-001", "Payments API");
        setField(asset, "id", ASSET_ID);
        setField(asset, "assetType", AssetType.SERVICE);
        var observation = new Observation(
                asset,
                ObservationCategory.CONFIGURATION,
                "patch_level",
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef-extra-preview",
                "collector",
                AS_OF.minusSeconds(3600));
        observation.setEvidenceRef("collector://patch");
        observation.setConfidence("HIGH");
        setField(observation, "id", OBSERVATION_ID);

        var control = new Control(project, "CTL-001", "Patch control", ControlFunction.PREVENTIVE);
        setField(control, "id", CONTROL_ID);
        var controlTest = new ControlTest(
                project,
                control,
                "CTEST-001",
                ControlTestMethodology.INSPECTION,
                ControlTestConclusion.EFFECTIVE,
                "auditor",
                LocalDate.parse("2026-05-31"));
        setField(controlTest, "id", TEST_ID);
        var cea = new ControlEffectivenessAssessment(
                project,
                control,
                "CEA-001",
                ControlEffectivenessRating.EFFECTIVE,
                ControlEffectivenessRating.EFFECTIVE,
                LocalDate.parse("2026-05-31"),
                "auditor");
        setField(cea, "id", UUID.fromString("00000000-0000-0000-0000-000000000801"));

        var scenario = new RiskScenario(
                project, "RS-001", "Risk", "Threat actor", "Abuse explorer", "Evidence surface", "Disclosure");
        setField(scenario, "id", UUID.fromString("00000000-0000-0000-0000-000000000901"));
        var methodology = new MethodologyProfile(project, "FAIR", "FAIR", "1.0", MethodologyFamily.FAIR);
        var assessment = new RiskAssessmentResult(project, scenario, methodology);
        assessment.setConfidence("HIGH");
        assessment.replaceObservations(List.of(observation));
        setField(assessment, "id", ASSESSMENT_ID);
        setField(assessment, "assessmentAt", AS_OF);

        var finding = new Finding(
                project,
                "FIND-001",
                "Patch drift",
                FindingType.CONTROL_DEFICIENCY,
                FindingSeverity.MEDIUM,
                "Patch drift");
        setField(finding, "id", FINDING_ID);
        var findingLink = new FindingLink(
                finding, FindingLinkTargetType.OBSERVATION, OBSERVATION_ID, null, FindingLinkType.EVIDENCED_BY);

        var artifact = new EvidenceArtifact(
                project,
                "EV-001",
                "Patch evidence",
                "Patch evidence summary",
                EvidenceType.OBSERVATION_SUMMARY,
                "collector",
                AS_OF.minusSeconds(120));
        artifact.setSources(List.of(
                new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, OBSERVATION_ID, null, "source"),
                new EvidenceSourceRef(EvidenceSourceKind.CONTROL_TEST, TEST_ID, null, "test"),
                new EvidenceSourceRef(EvidenceSourceKind.CONTROL_EFFECTIVENESS_ASSESSMENT, cea.getId(), null, "rating"),
                new EvidenceSourceRef(EvidenceSourceKind.RISK_ASSESSMENT_RESULT, ASSESSMENT_ID, null, "impact"),
                new EvidenceSourceRef(EvidenceSourceKind.FINDING, FINDING_ID, null, "finding")));
        artifact.setAssuranceLevel(com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel.L2);
        artifact.setConfidence("HIGH");
        setField(artifact, "id", ARTIFACT_ID);

        when(evidenceFreshnessAnalysisService.analyze(PROJECT_ID, AS_OF, 30, false, null, null))
                .thenReturn(freshnessResult());
        when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                .thenReturn(List.of(asset));
        when(evidenceArtifactRepository.findByProjectIdAndDerivedAtLessThanEqualOrderByDerivedAtDesc(PROJECT_ID, AS_OF))
                .thenReturn(List.of(artifact));
        when(observationRepository.findAllByIdInAndProjectId(List.of(OBSERVATION_ID), PROJECT_ID))
                .thenReturn(List.of(observation));
        when(controlTestRepository.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(
                        PROJECT_ID, LocalDate.parse("2026-06-01")))
                .thenReturn(List.of(controlTest));
        when(controlEffectivenessAssessmentRepository
                        .findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                                PROJECT_ID, LocalDate.parse("2026-06-01")))
                .thenReturn(List.of(cea));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(assessment));
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(finding));
        when(findingLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(findingLink));

        var result = service.workspace(PROJECT_ID, AS_OF, 30, false, null, null);

        assertThat(result.assetCount()).isEqualTo(1);
        assertThat(result.artifactCount()).isEqualTo(1);
        assertThat(result.observationCount()).isEqualTo(1);
        var evidence = result.evidenceArtifacts().getFirst();
        assertThat(evidence.freshnessState()).isEqualTo("FRESH");
        assertThat(evidence.assuranceLevel()).isEqualTo("L2");
        assertThat(evidence.sources()).extracting("sourceKind").contains("OBSERVATION", "FINDING");
        assertThat(evidence.affectedAssets()).extracting("targetIdentifier").contains("ASSET-001");
        assertThat(evidence.linkedControls()).extracting("targetIdentifier").contains("CTL-001");
        assertThat(evidence.downstreamAssessments())
                .extracting("targetIdentifier")
                .contains("RS-001");
        assertThat(evidence.linkedFindings()).extracting("targetIdentifier").contains("FIND-001");

        var obs = result.observations().getFirst();
        assertThat(obs.valuePreview()).endsWith("...");
        assertThat(obs.evidenceArtifacts()).extracting("targetIdentifier").contains("EV-001");
        assertThat(obs.downstreamAssessments()).extracting("targetIdentifier").contains("RS-001");
        assertThat(obs.linkedFindings()).extracting("targetIdentifier").contains("FIND-001");
    }

    @Test
    void workspaceRejectsNonPositiveFreshnessWindow() {
        assertThatThrownBy(() -> service.workspace(PROJECT_ID, AS_OF, 0, false, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("freshnessWindowDays");
    }

    private static EvidenceFreshnessResult freshnessResult() {
        return new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                AS_OF,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", AS_OF, 30, false, null, null),
                List.of(new EvidenceFreshnessResult.EvidenceArtifactFreshnessItem(
                        ARTIFACT_ID, "EV-001", "Patch evidence", AS_OF.minusSeconds(120), 0, "FRESH", null)),
                List.of(new EvidenceFreshnessResult.ObservationFreshnessItem(
                        OBSERVATION_ID,
                        ASSET_ID,
                        "ASSET-001",
                        "CONFIGURATION",
                        "patch_level",
                        AS_OF.minusSeconds(3600),
                        null,
                        0,
                        "FRESH")),
                List.of(new EvidenceFreshnessResult.ControlTestFreshnessItem(
                        TEST_ID, "CTEST-001", CONTROL_ID, "CTL-001", LocalDate.parse("2026-05-31"), 1, "FRESH")),
                new EvidenceFreshnessResult.EvidenceFreshnessCounts(3, 0, 0, 0, 3),
                List.of());
    }
}
