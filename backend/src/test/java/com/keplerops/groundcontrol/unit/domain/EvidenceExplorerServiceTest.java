package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerResult;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkType;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for EvidenceExplorerService — read-only composition that reuses
 * EvidenceFreshnessAnalysisService.analyze for freshness and enriches with provenance and downstream
 * finding impact per GC-Q012.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceExplorerServiceTest {

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @Mock
    private EvidenceArtifactRepository evidenceArtifactRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private FindingLinkRepository findingLinkRepository;

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @InjectMocks
    private EvidenceExplorerService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int WINDOW = 90;
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        // Default: empty repositories. analyze() is stubbed per test.
        when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(observationRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());
        when(findingLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private EvidenceFreshnessResult freshnessResult(
            List<EvidenceFreshnessResult.EvidenceArtifactFreshnessItem> artifacts,
            List<EvidenceFreshnessResult.ObservationFreshnessItem> observations,
            EvidenceFreshnessResult.EvidenceFreshnessCounts counts) {
        return new EvidenceFreshnessResult(
                "evidence_freshness",
                "ground-control",
                NOW,
                "evidence-freshness-projection-v1",
                new EvidenceFreshnessResult.Inputs("ground-control", NOW, WINDOW, true, null, null),
                artifacts,
                observations,
                List.of(),
                counts,
                List.of());
    }

    private EvidenceArtifact artifact(UUID id, EvidenceType type, List<EvidenceSourceRef> sources) {
        EvidenceArtifact a = new EvidenceArtifact(project, "EV-001", "Rollup", "summary", type, "ROLLUP", NOW);
        setField(a, "id", id);
        a.setSources(sources);
        return a;
    }

    private Observation observation(UUID id, OperationalAsset asset, String value, String source) {
        Observation o = new Observation(asset, ObservationCategory.CONFIGURATION, "os_version", value, source, NOW);
        setField(o, "id", id);
        o.setConfidence("HIGH");
        o.setEvidenceRef("https://example.com/proof");
        return o;
    }

    private OperationalAsset asset(UUID id) {
        OperationalAsset a = new OperationalAsset(project, "A-001", "Auth Service");
        setField(a, "id", id);
        return a;
    }

    private Finding finding(UUID id) {
        Finding f = new Finding(
                project, "FIND-001", "Downstream", FindingType.CONTROL_DEFICIENCY, FindingSeverity.HIGH, "desc");
        setField(f, "id", id);
        setField(f, "status", FindingStatus.OPEN);
        return f;
    }

    private FindingLink findingLink(Finding finding, FindingLinkTargetType targetType, UUID targetEntityId) {
        FindingLink l = new FindingLink(finding, targetType, targetEntityId, "", FindingLinkType.EVIDENCED_BY);
        setField(l, "id", UUID.randomUUID());
        return l;
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Nested
    class Composition {

        @Test
        void enrichesArtifactsAndObservationsWithProvenanceAndFindings() {
            UUID artifactId = UUID.randomUUID();
            UUID obsId = UUID.randomUUID();
            UUID findingId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();

            var artifactItem = new EvidenceFreshnessResult.EvidenceArtifactFreshnessItem(
                    artifactId, "EV-001", "Rollup", NOW, 3, "FRESH", null);
            var obsItem = new EvidenceFreshnessResult.ObservationFreshnessItem(
                    obsId, assetId, "A-001", "CONFIGURATION", "os_version", NOW, null, 120, "STALE");
            when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(freshnessResult(
                            List.of(artifactItem),
                            List.of(obsItem),
                            new EvidenceFreshnessResult.EvidenceFreshnessCounts(1, 1, 0, 0, 2)));

            var source = new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, obsId, null, "primary");
            when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(artifact(artifactId, EvidenceType.OBSERVATION_SUMMARY, List.of(source))));
            when(observationRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(List.of(observation(obsId, asset(assetId), "1.2.3", "scanner")));
            Finding finding = finding(findingId);
            when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(finding));
            when(findingLinkRepository.findByProjectId(PROJECT_ID))
                    .thenReturn(List.of(
                            findingLink(finding, FindingLinkTargetType.EVIDENCE, artifactId),
                            findingLink(finding, FindingLinkTargetType.OBSERVATION, obsId)));

            EvidenceExplorerResult result = service.explore(PROJECT_ID, null, WINDOW, null, null, true);

            assertThat(result.evidenceArtifacts()).hasSize(1);
            EvidenceExplorerResult.ExplorerArtifact artifact =
                    result.evidenceArtifacts().get(0);
            assertThat(artifact.evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
            assertThat(artifact.freshnessState()).isEqualTo("FRESH");
            assertThat(artifact.sources()).hasSize(1);
            assertThat(artifact.sources().get(0).sourceKind()).isEqualTo(EvidenceSourceKind.OBSERVATION);
            assertThat(artifact.downstreamFindings()).hasSize(1);
            assertThat(artifact.downstreamFindings().get(0).uid()).isEqualTo("FIND-001");

            assertThat(result.observations()).hasSize(1);
            EvidenceExplorerResult.ExplorerObservation obs =
                    result.observations().get(0);
            assertThat(obs.observationValue()).isEqualTo("1.2.3");
            assertThat(obs.source()).isEqualTo("scanner");
            assertThat(obs.freshnessState()).isEqualTo("STALE");
            assertThat(obs.downstreamFindings()).hasSize(1);

            assertThat(result.counts().fresh()).isEqualTo(1);
            assertThat(result.counts().currentlyValid()).isEqualTo(2);
        }

        @Test
        void surfacesDownstreamAssessmentsThatConsumedTheObservation() {
            UUID obsId = UUID.randomUUID();
            UUID assessmentId = UUID.randomUUID();
            UUID scenarioId = UUID.randomUUID();
            var obsItem = new EvidenceFreshnessResult.ObservationFreshnessItem(
                    obsId, UUID.randomUUID(), "A-001", "CONFIGURATION", "os_version", NOW, null, 5, "FRESH");
            when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(freshnessResult(
                            List.of(),
                            List.of(obsItem),
                            new EvidenceFreshnessResult.EvidenceFreshnessCounts(1, 0, 0, 0, 1)));

            // Mock the assessment + its observation set: the service only reads the getters.
            Observation consumed = mock(Observation.class);
            when(consumed.getId()).thenReturn(obsId);
            RiskAssessmentResult assessment = mock(RiskAssessmentResult.class);
            when(assessment.getId()).thenReturn(assessmentId);
            when(assessment.getObservations()).thenReturn(java.util.Set.of(consumed));
            when(assessment.getApprovalState()).thenReturn(RiskAssessmentApprovalStatus.APPROVED);
            when(assessment.getRiskScenario()).thenReturn(null);
            when(assessment.getMethodologyProfile()).thenReturn(null);
            when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(assessment));

            EvidenceExplorerResult result = service.explore(PROJECT_ID, null, WINDOW, null, null, true);

            EvidenceExplorerResult.ExplorerObservation obs =
                    result.observations().get(0);
            assertThat(obs.downstreamAssessments()).hasSize(1);
            assertThat(obs.downstreamAssessments().get(0).assessmentId()).isEqualTo(assessmentId);
            assertThat(obs.downstreamAssessments().get(0).approvalState())
                    .isEqualTo(RiskAssessmentApprovalStatus.APPROVED);
        }
    }

    @Nested
    class Bounding {

        @Test
        void capsObservationListingAndRecordsTruncationWhileCountsStayFull() {
            List<EvidenceFreshnessResult.ObservationFreshnessItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < EvidenceExplorerService.MAX_LISTING + 1; i++) {
                items.add(new EvidenceFreshnessResult.ObservationFreshnessItem(
                        UUID.randomUUID(), UUID.randomUUID(), "A", "CONFIGURATION", "k" + i, NOW, null, 1, "FRESH"));
            }
            when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(freshnessResult(
                            List.of(),
                            items,
                            new EvidenceFreshnessResult.EvidenceFreshnessCounts(items.size(), 0, 0, 0, items.size())));

            EvidenceExplorerResult result = service.explore(PROJECT_ID, null, WINDOW, null, null, true);

            assertThat(result.observations()).hasSize(EvidenceExplorerService.MAX_LISTING);
            assertThat(result.counts().fresh()).isEqualTo(items.size());
            assertThat(result.limitations()).anyMatch(l -> l.contains("observation listing truncated"));
        }
    }

    @Nested
    class Filtering {

        @Test
        void evidenceTypeNarrowsArtifactsAndRecordsLimitation() {
            UUID artifactId = UUID.randomUUID();
            var artifactItem = new EvidenceFreshnessResult.EvidenceArtifactFreshnessItem(
                    artifactId, "EV-001", "Rollup", NOW, 3, "FRESH", null);
            when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(freshnessResult(
                            List.of(artifactItem),
                            List.of(),
                            new EvidenceFreshnessResult.EvidenceFreshnessCounts(1, 0, 0, 0, 1)));
            when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(artifact(artifactId, EvidenceType.CONTROL_TEST_SUMMARY, List.of())));

            EvidenceExplorerResult result =
                    service.explore(PROJECT_ID, null, WINDOW, null, EvidenceType.OBSERVATION_SUMMARY, true);

            // Artifact is CONTROL_TEST_SUMMARY; filtered out by the OBSERVATION_SUMMARY narrow.
            assertThat(result.evidenceArtifacts()).isEmpty();
            assertThat(result.limitations()).anyMatch(l -> l.contains("evidenceType"));
            // Counts still reflect the full freshness set.
            assertThat(result.counts().fresh()).isEqualTo(1);
        }

        @Test
        void evidenceTypeNarrowsListingWhileCountsReflectFullSet() {
            // Two artifacts of different types; the freshness counts cover BOTH (fresh=2), but a
            // OBSERVATION_SUMMARY filter must narrow the listing to one while counts stay at the full set.
            UUID obsArtifactId = UUID.randomUUID();
            UUID ctrlArtifactId = UUID.randomUUID();
            var obsItem = new EvidenceFreshnessResult.EvidenceArtifactFreshnessItem(
                    obsArtifactId, "EV-OBS", "Obs rollup", NOW, 3, "FRESH", null);
            var ctrlItem = new EvidenceFreshnessResult.EvidenceArtifactFreshnessItem(
                    ctrlArtifactId, "EV-CTL", "Ctl rollup", NOW, 3, "FRESH", null);
            when(evidenceFreshnessAnalysisService.analyze(any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenReturn(freshnessResult(
                            List.of(obsItem, ctrlItem),
                            List.of(),
                            new EvidenceFreshnessResult.EvidenceFreshnessCounts(2, 0, 0, 0, 2)));
            when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(
                            artifact(obsArtifactId, EvidenceType.OBSERVATION_SUMMARY, List.of()),
                            artifact(ctrlArtifactId, EvidenceType.CONTROL_TEST_SUMMARY, List.of())));

            EvidenceExplorerResult result =
                    service.explore(PROJECT_ID, null, WINDOW, null, EvidenceType.OBSERVATION_SUMMARY, true);

            assertThat(result.evidenceArtifacts()).hasSize(1);
            assertThat(result.evidenceArtifacts().get(0).uid()).isEqualTo("EV-OBS");
            assertThat(result.counts().fresh()).isEqualTo(2);
            assertThat(result.counts().currentlyValid()).isEqualTo(2);
        }
    }
}
