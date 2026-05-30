package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceService;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
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
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import java.time.LocalDate;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for ControlWorkspaceService — read-only composition over the controls aggregate per
 * GC-Q011. Lenient strictness keeps the multi-repository baseline stubbing readable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControlWorkspaceServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ScopedControlImplementationRepository scopedControlImplementationRepository;

    @Mock
    private ControlTestRepository controlTestRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;

    @Mock
    private RiskControlMappingRepository riskControlMappingRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private FindingLinkRepository findingLinkRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @InjectMocks
    private ControlWorkspaceService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int WINDOW = 90;
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        stubAll(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Stubs the per-project loads. Mapping repo is always empty (mappingCount is exercised via the controller slice). */
    private void stubAll(
            List<Control> controls,
            List<ScopedControlImplementation> scoped,
            List<ControlTest> tests,
            List<ControlEffectivenessAssessment> assessments,
            List<Finding> findings,
            List<FindingLink> findingLinks) {
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(controls);
        when(scopedControlImplementationRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(scoped);
        when(controlTestRepository.findByProjectIdOrderByTestDateDesc(PROJECT_ID))
                .thenReturn(tests);
        when(controlEffectivenessAssessmentRepository.findByProjectIdOrderByAssessedAtDesc(PROJECT_ID))
                .thenReturn(assessments);
        when(riskControlMappingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(findings);
        when(findingLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(findingLinks);
        when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                .thenReturn(List.of());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Control control(String uid, ControlStatus status, String owner) {
        Control c = new Control(project, uid, "Control " + uid, ControlFunction.PREVENTIVE);
        setField(c, "id", UUID.randomUUID());
        setField(c, "status", status);
        c.setOwner(owner);
        return c;
    }

    private ScopedControlImplementation scoped(Control control, String uid, OperationalAsset asset) {
        ScopedControlImplementation impl = new ScopedControlImplementation(project, uid, control, "Impl " + uid);
        setField(impl, "id", UUID.randomUUID());
        if (asset != null) {
            setField(impl, "operationalAsset", asset);
        }
        return impl;
    }

    private ControlTest test(Control control, String uid, ControlTestConclusion conclusion, LocalDate date) {
        ControlTest t =
                new ControlTest(project, control, uid, ControlTestMethodology.INSPECTION, conclusion, "tester", date);
        setField(t, "id", UUID.randomUUID());
        return t;
    }

    private ControlEffectivenessAssessment assessment(
            Control control, String uid, ControlEffectivenessRating design, ControlEffectivenessRating operating) {
        ControlEffectivenessAssessment a = new ControlEffectivenessAssessment(
                project, control, uid, design, operating, LocalDate.parse("2026-05-01"), "assessor");
        setField(a, "id", UUID.randomUUID());
        return a;
    }

    private Finding finding(String uid, FindingStatus status) {
        Finding f = new Finding(
                project, uid, "Finding " + uid, FindingType.CONTROL_DEFICIENCY, FindingSeverity.HIGH, "desc");
        setField(f, "id", UUID.randomUUID());
        setField(f, "status", status);
        return f;
    }

    private FindingLink findingLink(Finding finding, UUID controlId) {
        FindingLink l = new FindingLink(finding, FindingLinkTargetType.CONTROL, controlId, "", FindingLinkType.AFFECTS);
        setField(l, "id", UUID.randomUUID());
        return l;
    }

    private OperationalAsset asset(String uid) {
        OperationalAsset a = new OperationalAsset(project, uid, "Asset " + uid);
        setField(a, "id", UUID.randomUUID());
        setField(a, "assetType", AssetType.SERVICE);
        return a;
    }

    private ControlWorkspaceResult.WorkspaceControl only(ControlWorkspaceResult result) {
        assertThat(result.controls()).hasSize(1);
        return result.controls().get(0);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Nested
    class EmptyProject {

        @Test
        void returnsEmptyComposition() {
            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null);
            assertThat(result.controls()).isEmpty();
            assertThat(result.ownerQueues()).isEmpty();
            assertThat(result.assets()).isEmpty();
        }
    }

    @Nested
    class Composition {

        @Test
        void composesTestsSummaryAndLatestAssessment() {
            Control c = control("CTL-001", ControlStatus.OPERATIONAL, "Alice");
            ControlTest newer = test(c, "CT-2", ControlTestConclusion.EFFECTIVE, LocalDate.parse("2026-05-02"));
            ControlTest older = test(c, "CT-1", ControlTestConclusion.INEFFECTIVE, LocalDate.parse("2026-05-01"));
            stubAll(
                    List.of(c),
                    List.of(),
                    List.of(newer, older), // repo returns testDate desc
                    List.of(assessment(
                            c, "CEA-1", ControlEffectivenessRating.EFFECTIVE, ControlEffectivenessRating.EFFECTIVE)),
                    List.of(),
                    List.of());

            ControlWorkspaceResult.WorkspaceControl control =
                    only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null));

            assertThat(control.tests()).hasSize(2);
            assertThat(control.testSummary().total()).isEqualTo(2);
            assertThat(control.testSummary().effective()).isEqualTo(1);
            assertThat(control.testSummary().ineffective()).isEqualTo(1);
            assertThat(control.testSummary().latestConclusion()).isEqualTo(ControlTestConclusion.EFFECTIVE);
            assertThat(control.latestAssessment()).isNotNull();
            assertThat(control.latestAssessment().uid()).isEqualTo("CEA-1");
        }

        @Test
        void surfacesControlExceptionsFromFindingLinks() {
            Control c = control("CTL-002", ControlStatus.IMPLEMENTED, "Bob");
            Finding f = finding("FIND-1", FindingStatus.OPEN);
            stubAll(List.of(c), List.of(), List.of(), List.of(), List.of(f), List.of(findingLink(f, c.getId())));

            ControlWorkspaceResult.WorkspaceControl control =
                    only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null));

            assertThat(control.exceptions()).hasSize(1);
            assertThat(control.exceptions().get(0).uid()).isEqualTo("FIND-1");
        }
    }

    @Nested
    class Attention {

        @Test
        void flagsOperationalControlWithNoAssessment() {
            Control c = control("CTL-003", ControlStatus.OPERATIONAL, "Alice");
            stubAll(List.of(c), List.of(), List.of(), List.of(), List.of(), List.of());

            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null);

            assertThat(only(result).needsAttention()).isTrue();
            assertThat(result.ownerQueues()).hasSize(1);
            assertThat(result.ownerQueues().get(0).attentionControls()).isEqualTo(1);
            assertThat(result.ownerQueues().get(0).attentionControlUids()).containsExactly("CTL-003");
        }

        @Test
        void doesNotFlagDraftControl() {
            Control c = control("CTL-004", ControlStatus.DRAFT, "Alice");
            stubAll(List.of(c), List.of(), List.of(), List.of(), List.of(), List.of());

            assertThat(only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null))
                            .needsAttention())
                    .isFalse();
        }

        @Test
        void staleEvidenceSetsIndicatorAndFlagsAttention() {
            Control c = control("CTL-005", ControlStatus.OPERATIONAL, "Alice");
            OperationalAsset a = asset("A-1");
            stubAll(
                    List.of(c),
                    List.of(scoped(c, "SCI-1", a)),
                    List.of(),
                    List.of(assessment(
                            c, "CEA-1", ControlEffectivenessRating.EFFECTIVE, ControlEffectivenessRating.EFFECTIVE)),
                    List.of(),
                    List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(PROJECT_ID), any(), anyInt(), eq(a.getId())))
                    .thenReturn(new AssetScopedFreshnessSummary(0, 1, 0, 0, "STALE"));

            ControlWorkspaceResult.WorkspaceControl control =
                    only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null));

            assertThat(control.staleIndicator()).isEqualTo("STALE");
            assertThat(control.linkedAssetIds()).containsExactly(a.getId());
            assertThat(control.needsAttention()).isTrue();
        }

        @Test
        void flagsIneffectiveDesignEffectivenessAlone() {
            Control c = control("CTL-D", ControlStatus.OPERATIONAL, "Alice");
            stubAll(
                    List.of(c),
                    List.of(),
                    List.of(),
                    List.of(assessment(
                            c, "CEA-D", ControlEffectivenessRating.INEFFECTIVE, ControlEffectivenessRating.EFFECTIVE)),
                    List.of(),
                    List.of());

            assertThat(only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null))
                            .needsAttention())
                    .isTrue();
        }

        @Test
        void flagsIneffectiveOperatingEffectivenessAlone() {
            Control c = control("CTL-O", ControlStatus.OPERATIONAL, "Alice");
            stubAll(
                    List.of(c),
                    List.of(),
                    List.of(),
                    List.of(assessment(
                            c, "CEA-O", ControlEffectivenessRating.EFFECTIVE, ControlEffectivenessRating.INEFFECTIVE)),
                    List.of(),
                    List.of());

            assertThat(only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null))
                            .needsAttention())
                    .isTrue();
        }

        @Test
        void flagsIneffectiveLatestTestConclusionAlone() {
            // Effective assessment, fresh evidence, no exceptions: only the latest test conclusion
            // drives attention. The latest test (by testDate desc) must be the INEFFECTIVE one.
            Control c = control("CTL-T", ControlStatus.OPERATIONAL, "Alice");
            ControlTest latest = test(c, "CT-2", ControlTestConclusion.INEFFECTIVE, LocalDate.parse("2026-05-02"));
            ControlTest older = test(c, "CT-1", ControlTestConclusion.EFFECTIVE, LocalDate.parse("2026-05-01"));
            stubAll(
                    List.of(c),
                    List.of(),
                    List.of(latest, older),
                    List.of(assessment(
                            c, "CEA-T", ControlEffectivenessRating.EFFECTIVE, ControlEffectivenessRating.EFFECTIVE)),
                    List.of(),
                    List.of());

            ControlWorkspaceResult.WorkspaceControl control =
                    only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null));
            assertThat(control.testSummary().latestConclusion()).isEqualTo(ControlTestConclusion.INEFFECTIVE);
            assertThat(control.needsAttention()).isTrue();
        }
    }

    @Nested
    class Freshness {

        @Test
        void picksWorstStateAcrossMultipleLinkedAssets() {
            // Two scoped implementations anchor two assets with EXPIRED and STALE evidence; the
            // dominant indicator must be the worst (EXPIRED > STALE). Guards the freshnessRank order.
            Control c = control("CTL-F", ControlStatus.IMPLEMENTED, "Alice");
            OperationalAsset expiredAsset = asset("A-EXP");
            OperationalAsset staleAsset = asset("A-STALE");
            stubAll(
                    List.of(c),
                    List.of(scoped(c, "SCI-E", expiredAsset), scoped(c, "SCI-S", staleAsset)),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(PROJECT_ID), any(), anyInt(), eq(expiredAsset.getId())))
                    .thenReturn(new AssetScopedFreshnessSummary(0, 0, 1, 0, "EXPIRED"));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(PROJECT_ID), any(), anyInt(), eq(staleAsset.getId())))
                    .thenReturn(new AssetScopedFreshnessSummary(0, 1, 0, 0, "STALE"));

            assertThat(only(service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null))
                            .staleIndicator())
                    .isEqualTo("EXPIRED");
        }
    }

    @Nested
    class Filtering {

        @Test
        void filtersByOwner() {
            Control alice = control("CTL-006", ControlStatus.OPERATIONAL, "Alice");
            Control bob = control("CTL-007", ControlStatus.OPERATIONAL, "Bob");
            stubAll(List.of(alice, bob), List.of(), List.of(), List.of(), List.of(), List.of());

            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, "Alice", null);

            assertThat(result.controls()).hasSize(1);
            assertThat(result.controls().get(0).uid()).isEqualTo("CTL-006");
        }

        @Test
        void filtersByStatus() {
            Control op = control("CTL-008", ControlStatus.OPERATIONAL, "Alice");
            Control draft = control("CTL-009", ControlStatus.DRAFT, "Alice");
            stubAll(List.of(op, draft), List.of(), List.of(), List.of(), List.of(), List.of());

            ControlWorkspaceResult result =
                    service.workspace(PROJECT_ID, null, WINDOW, ControlStatus.DRAFT, null, null, null);

            assertThat(result.controls()).hasSize(1);
            assertThat(result.controls().get(0).uid()).isEqualTo("CTL-009");
        }

        @Test
        void filtersByAssetId() {
            Control linked = control("CTL-010", ControlStatus.OPERATIONAL, "Alice");
            Control unlinked = control("CTL-011", ControlStatus.OPERATIONAL, "Alice");
            OperationalAsset a = asset("A-2");
            stubAll(
                    List.of(linked, unlinked),
                    List.of(scoped(linked, "SCI-2", a)),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
            when(operationalAssetRepository.findByIdAndProjectId(a.getId(), PROJECT_ID))
                    .thenReturn(Optional.of(a));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(PROJECT_ID), any(), anyInt(), eq(a.getId())))
                    .thenReturn(new AssetScopedFreshnessSummary(1, 0, 0, 0, "FRESH"));

            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, null, a.getId());

            assertThat(result.controls()).hasSize(1);
            assertThat(result.controls().get(0).uid()).isEqualTo("CTL-010");
        }
    }

    @Nested
    class OwnerQueues {

        @Test
        void bucketsBlankOwnerAsUnassigned() {
            Control c = control("CTL-012", ControlStatus.IMPLEMENTED, "");
            stubAll(List.of(c), List.of(), List.of(), List.of(), List.of(), List.of());

            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null);

            assertThat(result.ownerQueues()).hasSize(1);
            assertThat(result.ownerQueues().get(0).owner()).isEqualTo("Unassigned");
        }

        @Test
        void bucketsNullOwnerAsUnassigned() {
            Control c = control("CTL-013", ControlStatus.IMPLEMENTED, null);
            stubAll(List.of(c), List.of(), List.of(), List.of(), List.of(), List.of());

            ControlWorkspaceResult result = service.workspace(PROJECT_ID, null, WINDOW, null, null, null, null);

            assertThat(result.ownerQueues()).hasSize(1);
            assertThat(result.ownerQueues().get(0).owner()).isEqualTo("Unassigned");
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsNonPositiveFreshnessWindow() {
            assertThatThrownBy(() -> service.workspace(PROJECT_ID, null, 0, null, null, null, null))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void rejectsAssetIdNotInProject() {
            UUID missing = UUID.randomUUID();
            when(operationalAssetRepository.findByIdAndProjectId(missing, PROJECT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.workspace(PROJECT_ID, null, WINDOW, null, null, null, missing))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
