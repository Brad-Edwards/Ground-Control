package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetScope;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.PortfolioAggregationService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.PortfolioSummaryResult;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for PortfolioAggregationService — read-only portfolio projection per GC-Q013. Control
 * health, finding trends, and asset criticality are exercised with real entities; risk-posture and
 * methodology paths are covered via empty repositories (no fragile multi-aggregate construction).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortfolioAggregationServiceTest {

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;

    @Mock
    private RiskControlMappingRepository riskControlMappingRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @InjectMocks
    private PortfolioAggregationService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int WINDOW = 90;
    private static final Instant AS_OF = Instant.parse("2026-05-18T00:00:00Z");
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);

        when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(freshness());

        // Default empty repositories; tests override the ones they care about.
        when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());
        when(controlEffectivenessAssessmentRepository.findByProjectIdOrderByAssessedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(riskControlMappingRepository.findUnmappedControlIds(PROJECT_ID)).thenReturn(List.of());
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());
        when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                .thenReturn(List.of());
        when(methodologyProfileRepository.findByProjectIdOrderByNameAscVersionDesc(PROJECT_ID))
                .thenReturn(List.of());
    }

    private EvidenceFreshnessResult freshness() {
        return new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                AS_OF,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", AS_OF, WINDOW, true, null, null),
                List.of(),
                List.of(),
                List.of(),
                new EvidenceFreshnessResult.EvidenceFreshnessCounts(2, 1, 0, 0, 3),
                List.of());
    }

    private Control control(String uid, ControlStatus status) {
        Control c = new Control(project, uid, "Control " + uid, ControlFunction.PREVENTIVE);
        setField(c, "id", UUID.randomUUID());
        setField(c, "status", status);
        return c;
    }

    private ControlEffectivenessAssessment assessment(
            Control control, ControlEffectivenessRating design, ControlEffectivenessRating operating) {
        ControlEffectivenessAssessment a = new ControlEffectivenessAssessment(
                project,
                control,
                "CEA-" + control.getUid(),
                design,
                operating,
                LocalDate.parse("2026-05-01"),
                "assessor");
        setField(a, "id", UUID.randomUUID());
        return a;
    }

    private Finding finding(String uid, FindingSeverity severity, FindingStatus status, LocalDate dueDate) {
        Finding f = new Finding(project, uid, "Finding " + uid, FindingType.CONTROL_DEFICIENCY, severity, "desc");
        setField(f, "id", UUID.randomUUID());
        setField(f, "status", status);
        if (dueDate != null) {
            f.setDueDate(dueDate);
        }
        return f;
    }

    private RiskRegisterRecord registerRecord(String uid, Instant nextReviewAt) {
        RiskRegisterRecord r = new RiskRegisterRecord(project, uid, "Register " + uid);
        setField(r, "id", UUID.randomUUID());
        r.setNextReviewAt(nextReviewAt);
        return r;
    }

    private OperationalAsset asset(String uid, AssetCriticality criticality) {
        OperationalAsset a = new OperationalAsset(project, uid, "Asset " + uid);
        setField(a, "id", UUID.randomUUID());
        setField(a, "criticality", criticality);
        setField(a, "environment", AssetEnvironment.PRODUCTION);
        setField(a, "scopeDesignation", AssetScope.IN_SCOPE);
        return a;
    }

    @Test
    void reusesEvidenceFreshnessCounts() {
        PortfolioSummaryResult result = service.summarize(PROJECT_ID, AS_OF, WINDOW);
        assertThat(result.evidenceFreshness().fresh()).isEqualTo(2);
        assertThat(result.evidenceFreshness().currentlyValid()).isEqualTo(3);
        assertThat(result.project()).isEqualTo("ground-control");
    }

    @Test
    void controlHealthCountsStatusEffectivenessUnassessedAndUnmapped() {
        Control assessed = control("CTL-001", ControlStatus.OPERATIONAL);
        Control unassessed = control("CTL-002", ControlStatus.IMPLEMENTED);
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(assessed, unassessed));
        when(controlEffectivenessAssessmentRepository.findByProjectIdOrderByAssessedAtDesc(PROJECT_ID))
                .thenReturn(List.of(assessment(
                        assessed,
                        ControlEffectivenessRating.EFFECTIVE,
                        ControlEffectivenessRating.PARTIALLY_EFFECTIVE)));
        when(riskControlMappingRepository.findUnmappedControlIds(PROJECT_ID)).thenReturn(List.of(unassessed.getId()));

        PortfolioSummaryResult.ControlHealth health =
                service.summarize(PROJECT_ID, AS_OF, WINDOW).controlHealth();

        assertThat(health.totalControls()).isEqualTo(2);
        assertThat(health.controlsByStatus()).containsEntry("OPERATIONAL", 1).containsEntry("IMPLEMENTED", 1);
        assertThat(health.designEffectivenessDistribution()).containsEntry("EFFECTIVE", 1);
        assertThat(health.operatingEffectivenessDistribution()).containsEntry("PARTIALLY_EFFECTIVE", 1);
        assertThat(health.unassessedControls()).isEqualTo(1);
        assertThat(health.unassessedControlUids()).containsExactly("CTL-002");
        assertThat(health.unmappedControls()).isEqualTo(1);
        assertThat(health.unmappedControlUids()).containsExactly("CTL-002");
    }

    @Test
    void riskPostureCountsOverdueReviewsAndExposesTheirUids() {
        Instant past = Instant.parse("2026-01-01T00:00:00Z");
        Instant future = Instant.parse("2026-12-01T00:00:00Z");
        when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(registerRecord("RRR-001", past), registerRecord("RRR-002", future)));

        PortfolioSummaryResult.RiskPosture posture =
                service.summarize(PROJECT_ID, AS_OF, WINDOW).riskPosture();

        assertThat(posture.totalRegisterRecords()).isEqualTo(2);
        assertThat(posture.overdueReviews()).isEqualTo(1);
        assertThat(posture.overdueRegisterRecordUids()).containsExactly("RRR-001");
    }

    @Test
    void findingTrendsCountsSeverityStatusOpenAndOverdue() {
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(
                        finding("F-1", FindingSeverity.HIGH, FindingStatus.OPEN, LocalDate.parse("2026-01-01")),
                        finding(
                                "F-2",
                                FindingSeverity.LOW,
                                FindingStatus.VERIFIED_CLOSED,
                                LocalDate.parse("2026-01-01")),
                        finding("F-3", FindingSeverity.HIGH, FindingStatus.REMEDIATION_IN_PROGRESS, null)));

        PortfolioSummaryResult.FindingTrends trends =
                service.summarize(PROJECT_ID, AS_OF, WINDOW).findingTrends();

        assertThat(trends.totalFindings()).isEqualTo(3);
        assertThat(trends.bySeverity()).containsEntry("HIGH", 2).containsEntry("LOW", 1);
        assertThat(trends.openCount()).isEqualTo(1);
        assertThat(trends.openFindingUids()).containsExactly("F-1");
        // F-1 is OPEN and past due; F-2 is closed (excluded); F-3 has no due date.
        assertThat(trends.overdueCount()).isEqualTo(1);
        assertThat(trends.overdueFindingUids()).containsExactly("F-1");
    }

    @Test
    void assetCriticalityCountsAndListsCriticalAssets() {
        when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                .thenReturn(List.of(
                        asset("A-001", AssetCriticality.CRITICAL),
                        asset("A-002", AssetCriticality.CRITICAL),
                        asset("A-003", AssetCriticality.LOW)));

        PortfolioSummaryResult.AssetCriticality criticality =
                service.summarize(PROJECT_ID, AS_OF, WINDOW).assetCriticality();

        assertThat(criticality.totalAssets()).isEqualTo(3);
        assertThat(criticality.byCriticality()).containsEntry("CRITICAL", 2).containsEntry("LOW", 1);
        assertThat(criticality.byEnvironment()).containsEntry("PRODUCTION", 3);
        assertThat(criticality.criticalAssetUids()).containsExactly("A-001", "A-002");
    }

    @Test
    void emptyProjectYieldsZeroedDimensions() {
        PortfolioSummaryResult result = service.summarize(PROJECT_ID, AS_OF, WINDOW);
        assertThat(result.riskPosture().totalScenarios()).isZero();
        assertThat(result.controlHealth().totalControls()).isZero();
        assertThat(result.findingTrends().totalFindings()).isZero();
        assertThat(result.assetCriticality().totalAssets()).isZero();
        assertThat(result.methodologySummaries()).isEmpty();
    }
}
