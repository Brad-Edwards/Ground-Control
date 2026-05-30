package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioWorkspaceService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for RiskScenarioWorkspaceService — read-only composition over existing aggregates
 * per GC-Q009.
 */
@ExtendWith(MockitoExtension.class)
class RiskScenarioWorkspaceServiceTest {

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @InjectMocks
    private RiskScenarioWorkspaceService service;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final int WINDOW = 90;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private RiskScenario makeScenario(String uid) {
        var rs = new RiskScenario(
                project,
                uid,
                "Title " + uid,
                "External threat actor",
                "Credential stuffing attack",
                "Customer auth portal",
                "Data breach");
        rs.setTimeHorizon("12 months");
        setField(rs, "id", UUID.randomUUID());
        setField(rs, "createdAt", NOW);
        setField(rs, "updatedAt", NOW);
        return rs;
    }

    private RiskScenarioLink makeLink(
            RiskScenario rs, RiskScenarioLinkTargetType type, UUID entityId, String identifier) {
        var link = new RiskScenarioLink(rs, type, entityId, identifier, RiskScenarioLinkType.MITIGATED_BY);
        link.setTargetTitle("Title");
        link.setTargetUrl("https://example.com");
        setField(link, "id", UUID.randomUUID());
        return link;
    }

    private OperationalAsset makeAsset(String uid, AssetType type) {
        var a = new OperationalAsset(project, uid, "Asset " + uid);
        a.setAssetType(type);
        setField(a, "id", UUID.randomUUID());
        setField(a, "createdAt", NOW);
        setField(a, "updatedAt", NOW);
        return a;
    }

    private MethodologyProfile makeProfile() {
        var mp = new MethodologyProfile(project, "fair", "FAIR-CRST", "1.0", MethodologyFamily.FAIR);
        setField(mp, "id", UUID.randomUUID());
        setField(mp, "createdAt", NOW);
        setField(mp, "updatedAt", NOW);
        return mp;
    }

    private RiskAssessmentResult makeAssessment(RiskScenario rs, MethodologyProfile mp) {
        var r = new RiskAssessmentResult(project, rs, mp);
        r.setAssessmentAt(NOW);
        r.setConfidence("HIGH");
        setField(r, "id", UUID.randomUUID());
        setField(r, "createdAt", NOW);
        setField(r, "updatedAt", NOW);
        return r;
    }

    private RiskRegisterRecord makeRegisterRecord(String uid) {
        var r = new RiskRegisterRecord(project, uid, "Register " + uid);
        setField(r, "id", UUID.randomUUID());
        setField(r, "createdAt", NOW);
        setField(r, "updatedAt", NOW);
        return r;
    }

    private TreatmentPlan makeTreatment(RiskRegisterRecord rrr, String uid) {
        var t = new TreatmentPlan(project, uid, "Treatment " + uid, rrr, TreatmentStrategy.MITIGATE);
        setField(t, "id", UUID.randomUUID());
        setField(t, "createdAt", NOW);
        setField(t, "updatedAt", NOW);
        return t;
    }

    private AssetScopedFreshnessSummary freshSummary() {
        return new AssetScopedFreshnessSummary(3, 0, 0, 0, "FRESH");
    }

    private AssetScopedFreshnessSummary staleSummary() {
        return new AssetScopedFreshnessSummary(0, 2, 0, 0, "STALE");
    }

    private AssetScopedFreshnessSummary expiredSummary() {
        return new AssetScopedFreshnessSummary(0, 0, 0, 1, "EXPIRED");
    }

    /** Default stub set that returns empty for all repos. */
    private void stubEmpty() {
        when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                .thenReturn(List.of());
    }

    @Nested
    class EmptyProject {

        @Test
        void returnsEmptyResultForProjectWithNoEntities() {
            stubEmpty();

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).isEmpty();
            assertThat(result.assets()).isEmpty();
            assertThat(result.scenarioCount()).isZero();
            assertThat(result.assetCount()).isZero();
        }
    }

    @Nested
    class ScenarioInclusion {

        @Test
        void includesScenarioFields() {
            var rs = makeScenario("RS-001");
            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            var s = result.scenarios().get(0);
            assertThat(s.uid()).isEqualTo("RS-001");
            assertThat(s.title()).isEqualTo("Title RS-001");
            assertThat(s.fairSentence()).contains("External threat actor").contains("Customer auth portal");
        }
    }

    @Nested
    class AssetPartition {

        @Test
        void includesAssetsWithBoundaryFlag() {
            var service2 = makeAsset("A-001", AssetType.SERVICE);
            var boundary = makeAsset("B-001", AssetType.BOUNDARY);
            stubEmpty();
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of(service2, boundary));

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.assets()).hasSize(2);
            var svcItem = result.assets().stream()
                    .filter(a -> a.uid().equals("A-001"))
                    .findFirst()
                    .orElseThrow();
            assertThat(svcItem.isBoundary()).isFalse();
            var bItem = result.assets().stream()
                    .filter(a -> a.uid().equals("B-001"))
                    .findFirst()
                    .orElseThrow();
            assertThat(bItem.isBoundary()).isTrue();
        }
    }

    @Nested
    class LinkBucketing {

        @Test
        void bucketsLinksByTargetType() {
            var rs = makeScenario("RS-001");
            UUID assetEntityId = UUID.randomUUID();
            UUID controlEntityId = UUID.randomUUID();
            UUID findingEntityId = UUID.randomUUID();
            UUID evidenceEntityId = UUID.randomUUID();
            UUID reqEntityId = UUID.randomUUID();

            var assetLink = makeLink(rs, RiskScenarioLinkTargetType.ASSET, assetEntityId, null);
            var controlLink = makeLink(rs, RiskScenarioLinkTargetType.CONTROL, controlEntityId, "CTL-001");
            var findingLink = makeLink(rs, RiskScenarioLinkTargetType.FINDING, findingEntityId, "FIND-001");
            var evidenceLink = makeLink(rs, RiskScenarioLinkTargetType.EVIDENCE, evidenceEntityId, "EV-001");
            var reqLink = makeLink(rs, RiskScenarioLinkTargetType.REQUIREMENT, reqEntityId, "GC-Q009");

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId))
                    .thenReturn(List.of(assetLink, controlLink, findingLink, evidenceLink, reqLink));
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            var s = result.scenarios().get(0);
            assertThat(s.linkedAssetIds()).containsExactly(assetEntityId);
            assertThat(s.linkedControls()).hasSize(1);
            assertThat(s.linkedControls().get(0).targetEntityId()).isEqualTo(controlEntityId);
            assertThat(s.linkedFindings()).hasSize(1);
            assertThat(s.linkedFindings().get(0).targetEntityId()).isEqualTo(findingEntityId);
            assertThat(s.linkedEvidence()).hasSize(1);
            assertThat(s.linkedEvidence().get(0).targetEntityId()).isEqualTo(evidenceEntityId);
            assertThat(s.linkedRequirements()).hasSize(1);
            assertThat(s.linkedRequirements().get(0).targetEntityId()).isEqualTo(reqEntityId);
        }
    }

    @Nested
    class AssetFilter {

        @Test
        void includesOnlyScenariosLinkedToTheGivenAsset() {
            UUID assetId = UUID.randomUUID();
            var matching = makeScenario("RS-001");
            var other = makeScenario("RS-002");
            var assetLink = makeLink(matching, RiskScenarioLinkTargetType.ASSET, assetId, null);

            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(makeAsset("A-001", AssetType.SERVICE)));
            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(matching, other));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetId)))
                    .thenReturn(freshSummary());

            // Exercises the nominal assetId filter path (hasAssetLink), not just the
            // not-found guard: only the scenario carrying an ASSET link to assetId is kept
            // (test-quality finding, cycle 2).
            var result = service.workspace(projectId, null, WINDOW, assetId, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            assertThat(result.scenarios().get(0).uid()).isEqualTo("RS-001");
        }
    }

    @Nested
    class AssessmentGrouping {

        @Test
        void groupsAssessmentsPerScenario() {
            var rs = makeScenario("RS-001");
            var mp = makeProfile();
            var assessment = makeAssessment(rs, mp);

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(assessment));
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            var s = result.scenarios().get(0);
            assertThat(s.assessments()).hasSize(1);
            var a = s.assessments().get(0);
            assertThat(a.methodologyProfileName()).isEqualTo("FAIR-CRST");
            assertThat(a.approvalState()).isEqualTo(RiskAssessmentApprovalStatus.DRAFT);
            assertThat(a.confidence()).isEqualTo("HIGH");
            // hasComputedOutputs should be false when computedOutputs is null
            assertThat(a.hasComputedOutputs()).isFalse();
        }

        @Test
        void hasComputedOutputsTrueWhenPresent() {
            var rs = makeScenario("RS-001");
            var mp = makeProfile();
            var assessment = makeAssessment(rs, mp);
            assessment.setComputedOutputs(java.util.Map.of("score", 42));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(assessment));
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).assessments().get(0).hasComputedOutputs())
                    .isTrue();
        }
    }

    @Nested
    class TreatmentGrouping {

        @Test
        void groupsTreatmentsPerScenario() {
            var rs = makeScenario("RS-001");
            var rrr = makeRegisterRecord("RRR-001");
            var treatment = makeTreatment(rrr, "TP-001");
            treatment.setRiskScenario(rs);

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(treatment));
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            var s = result.scenarios().get(0);
            assertThat(s.treatments()).hasSize(1);
            var t = s.treatments().get(0);
            assertThat(t.uid()).isEqualTo("TP-001");
            assertThat(t.strategy()).isEqualTo(TreatmentStrategy.MITIGATE);
        }
    }

    @Nested
    class RegisterMembership {

        @Test
        void groupsRegisterRecordsPerScenario() {
            var rs = makeScenario("RS-001");
            var rrr = makeRegisterRecord("RRR-001");
            rrr.replaceRiskScenarios(List.of(rs));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rrr));
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios()).hasSize(1);
            var s = result.scenarios().get(0);
            assertThat(s.registerRecords()).hasSize(1);
            assertThat(s.registerRecords().get(0).uid()).isEqualTo("RRR-001");
        }
    }

    @Nested
    class ReviewIndicator {

        @Test
        void reviewIndicatorIsNoSignalWhenNothingLinked() {
            var rs = makeScenario("RS-001");
            stubEmpty();
            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("NO_SIGNAL");
        }

        @Test
        void reviewIndicatorIsCurrentWhenAssessmentsPresentNoneTriggered() {
            var rs = makeScenario("RS-001");
            var mp = makeProfile();
            var assessment = makeAssessment(rs, mp);

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(assessment));
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("CURRENT");
        }

        @Test
        void reviewIndicatorIsReassessmentRequiredWhenFlagSet() {
            var rs = makeScenario("RS-001");
            var mp = makeProfile();
            var assessment = makeAssessment(rs, mp);
            assessment.setReassessmentRequiredAt(NOW.minusSeconds(1));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(assessment));
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("REASSESSMENT_REQUIRED");
        }

        @Test
        void reviewIndicatorIsReviewDueWhenRegisterNextReviewAtBeforeAsOf() {
            var rs = makeScenario("RS-001");
            var rrr = makeRegisterRecord("RRR-001");
            rrr.setNextReviewAt(NOW.minusSeconds(1)); // in the past relative to asOf=NOW
            rrr.replaceRiskScenarios(List.of(rs));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rrr));
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("REVIEW_DUE");
        }

        @Test
        void reviewIndicatorIsEvidenceStaleWhenLinkedAssetIsStale() {
            var rs = makeScenario("RS-001");
            UUID assetEntityId = UUID.randomUUID();
            var assetLink = makeLink(rs, RiskScenarioLinkTargetType.ASSET, assetEntityId, null);

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(staleSummary());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("EVIDENCE_STALE");
        }

        @Test
        void reassessmentRequiredBeatsReviewDue() {
            var rs = makeScenario("RS-001");
            var mp = makeProfile();
            var assessment = makeAssessment(rs, mp);
            assessment.setReassessmentRequiredAt(NOW.minusSeconds(1));

            var rrr = makeRegisterRecord("RRR-001");
            rrr.setNextReviewAt(NOW.minusSeconds(1));
            rrr.replaceRiskScenarios(List.of(rs));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(assessment));
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rrr));
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("REASSESSMENT_REQUIRED");
        }

        @Test
        void reviewDueBeatsEvidenceStale() {
            var rs = makeScenario("RS-001");
            UUID assetEntityId = UUID.randomUUID();
            var assetLink = makeLink(rs, RiskScenarioLinkTargetType.ASSET, assetEntityId, null);

            var rrr = makeRegisterRecord("RRR-001");
            rrr.setNextReviewAt(NOW.minusSeconds(1));
            rrr.replaceRiskScenarios(List.of(rs));

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rrr));
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(staleSummary());

            var result = service.workspace(projectId, NOW, WINDOW, null, null, null, null, null, List.of());

            assertThat(result.scenarios().get(0).reviewIndicator()).isEqualTo("REVIEW_DUE");
        }
    }

    @Nested
    class StatusFilter {

        @Test
        void filtersScenariosByStatus() {
            var rs1 = makeScenario("RS-001");
            var rs2 = makeScenario("RS-002");
            rs2.transitionStatus(com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus.ACTIVE);

            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(rs1, rs2));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(
                    projectId,
                    null,
                    WINDOW,
                    null,
                    com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus.ACTIVE,
                    null,
                    null,
                    null,
                    List.of());

            assertThat(result.scenarios()).hasSize(1);
            assertThat(result.scenarios().get(0).uid()).isEqualTo("RS-002");
        }
    }

    @Nested
    class CompareSubset {

        @Test
        void filtersToCompareSubset() {
            var rs1 = makeScenario("RS-001");
            var rs2 = makeScenario("RS-002");
            UUID id1 = rs1.getId();

            when(riskScenarioRepository.findByIdInAndProjectId(List.of(id1), projectId))
                    .thenReturn(List.of(rs1));
            when(riskScenarioLinkRepository.findByProjectId(projectId)).thenReturn(List.of());
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of(id1));

            assertThat(result.scenarios()).hasSize(1);
            assertThat(result.scenarios().get(0).uid()).isEqualTo("RS-001");
        }

        @Test
        void throwsDomainValidationExceptionForMoreThan10CompareIds() {
            var ids = new java.util.ArrayList<UUID>();
            for (int i = 0; i < 11; i++) ids.add(UUID.randomUUID());

            assertThatThrownBy(() -> service.workspace(projectId, null, WINDOW, null, null, null, null, null, ids))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("compare");
        }
    }

    @Nested
    class Validation {

        @Test
        void throwsDomainValidationExceptionWhenFreshnessWindowIsZero() {
            assertThatThrownBy(() -> service.workspace(projectId, null, 0, null, null, null, null, null, List.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("freshnessWindowDays");
        }

        @Test
        void throwsDomainValidationExceptionWhenFreshnessWindowIsNegative() {
            assertThatThrownBy(() -> service.workspace(projectId, null, -1, null, null, null, null, null, List.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("freshnessWindowDays");
        }

        @Test
        void throwsNotFoundForAssetIdNotInProject() {
            UUID badAssetId = UUID.randomUUID();
            when(operationalAssetRepository.findByIdAndProjectId(badAssetId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.workspace(projectId, null, WINDOW, badAssetId, null, null, null, null, List.of()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForMethodologyProfileIdNotInProject() {
            UUID badMpId = UUID.randomUUID();
            when(methodologyProfileRepository.findByIdAndProjectId(badMpId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.workspace(projectId, null, WINDOW, null, null, badMpId, null, null, List.of()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectIsolation {

        @Test
        void callsRepositoriesWithGivenProjectId() {
            stubEmpty();

            var result = service.workspace(projectId, null, WINDOW, null, null, null, null, null, List.of());

            org.mockito.Mockito.verify(riskScenarioRepository).findByProjectIdOrderByCreatedAtDesc(projectId);
            org.mockito.Mockito.verify(riskScenarioLinkRepository).findByProjectId(projectId);
            org.mockito.Mockito.verify(riskAssessmentResultRepository)
                    .findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
            org.mockito.Mockito.verify(treatmentPlanRepository).findByProjectIdOrderByCreatedAtDesc(projectId);
            org.mockito.Mockito.verify(riskRegisterRecordRepository)
                    .findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);
            org.mockito.Mockito.verify(operationalAssetRepository).findByProjectIdAndArchivedAtIsNull(projectId);

            // Project isolation must also produce a correctly shaped empty result, not just
            // call the repositories — a service that queried correctly but mis-assembled the
            // result would otherwise pass (test-quality finding F2, issue #747).
            assertThat(result.scenarios()).isEmpty();
            assertThat(result.assets()).isEmpty();
            assertThat(result.scenarioCount()).isZero();
            assertThat(result.assetCount()).isZero();
        }
    }
}
