package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamEffectDimension;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FairCamControlAnalyticsServiceTest {

    @Mock
    private RiskControlMappingRepository mappingRepo;

    @Mock
    private ControlEffectivenessAssessmentRepository assessmentRepo;

    @Mock
    private ControlTestRepository testRepo;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private FairCamControlAnalyticsService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant AS_OF = Instant.parse("2026-06-21T00:00:00Z");
    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 6, 21);

    private Project project;
    private Control control;

    @BeforeEach
    void setUp() {
        project = makeProject();
        control = makeControl(project, "CTRL-1", "Test Control");
    }

    @Test
    void projectNotFound_throwsNotFoundException() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Project not found");
    }

    @Test
    void noMappings_returnsEmptyControlsList() {
        stubProject();
        stubNoMappings();
        stubNoAssessments();
        stubNoTests();

        FairCamControlAnalyticsResult result =
                service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

        assertThat(result.analysisKind()).isEqualTo("fair_cam_control_analytics");
        assertThat(result.controls()).isEmpty();
        assertThat(result.counts().total()).isEqualTo(0);
    }

    @Nested
    class DomainAttribution {

        @Test
        void methodologyInfluence_fairCamDomain_usedAsPrimary() {
            stubProject();
            Map<String, Object> influence = new HashMap<>();
            influence.put("fair_cam_domain", "loss_event_control");
            RiskControlMapping mapping = mappingWithInfluence(influence, MappingControlRole.DIRECTIVE);
            stubMappings(List.of(mapping));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            assertThat(result.controls()).hasSize(1);
            var item = result.controls().get(0);
            assertThat(item.domainAttributions()).hasSize(1);
            assertThat(item.domainAttributions().get(0).domain()).isEqualTo(FairCamControlDomain.LOSS_EVENT_CONTROL);
            assertThat(item.domainAttributions().get(0).source()).isEqualTo("methodology_influence");
            // No role-derived limitation when primary path used
            assertThat(item.limitations()).noneMatch(l -> l.contains("mapping_control_role"));
        }

        @Test
        void noMethodologyInfluence_fallsBackToRoleDerived_withLimitation() {
            stubProject();
            RiskControlMapping mapping = mappingWithInfluence(null, MappingControlRole.PREVENTIVE);
            stubMappings(List.of(mapping));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.domainAttributions()).hasSize(1);
            assertThat(item.domainAttributions().get(0).domain()).isEqualTo(FairCamControlDomain.LOSS_EVENT_CONTROL);
            assertThat(item.domainAttributions().get(0).source()).isEqualTo("mapping_control_role");
            assertThat(item.limitations()).anyMatch(l -> l.contains("mapping_control_role"));
        }

        @Test
        void preventiveRole_mapsToLossEventControl() {
            assertRoleMapsToDomain(MappingControlRole.PREVENTIVE, FairCamControlDomain.LOSS_EVENT_CONTROL);
        }

        @Test
        void detectiveRole_mapsToLossEventControl() {
            assertRoleMapsToDomain(MappingControlRole.DETECTIVE, FairCamControlDomain.LOSS_EVENT_CONTROL);
        }

        @Test
        void deterrentRole_mapsToLossEventControl() {
            assertRoleMapsToDomain(MappingControlRole.DETERRENT, FairCamControlDomain.LOSS_EVENT_CONTROL);
        }

        @Test
        void correctiveRole_mapsToVarianceManagementControl() {
            assertRoleMapsToDomain(MappingControlRole.CORRECTIVE, FairCamControlDomain.VARIANCE_MANAGEMENT_CONTROL);
        }

        @Test
        void recoveryRole_mapsToVarianceManagementControl() {
            assertRoleMapsToDomain(MappingControlRole.RECOVERY, FairCamControlDomain.VARIANCE_MANAGEMENT_CONTROL);
        }

        @Test
        void compensatingRole_mapsToVarianceManagementControl() {
            assertRoleMapsToDomain(MappingControlRole.COMPENSATING, FairCamControlDomain.VARIANCE_MANAGEMENT_CONTROL);
        }

        @Test
        void directiveRole_mapsToDecisionSupportControl() {
            assertRoleMapsToDomain(MappingControlRole.DIRECTIVE, FairCamControlDomain.DECISION_SUPPORT_CONTROL);
        }

        private void assertRoleMapsToDomain(MappingControlRole role, FairCamControlDomain expectedDomain) {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, role)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            assertThat(result.controls().get(0).domainAttributions().get(0).domain())
                    .isEqualTo(expectedDomain);
        }
    }

    @Nested
    class Capability {

        @Test
        void latestDesignEffectiveness_returnedAsCapability() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            ControlEffectivenessAssessment assessment = makeAssessment(
                    control,
                    ControlEffectivenessRating.EFFECTIVE,
                    ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                    AS_OF_DATE);
            stubAssessments(List.of(assessment));
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.capability()).isNotNull();
            assertThat(item.capability().value()).isEqualTo("EFFECTIVE");
            assertThat(item.capability().scale()).isEqualTo("ordinal");
            assertThat(item.capability().units()).isEqualTo("ControlEffectivenessRating");
            assertThat(item.limitations()).noneMatch(l -> l.contains("capability not derivable"));
        }

        @Test
        void noAssessment_capabilityNullWithLimitation() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.capability().value()).isNull();
            assertThat(item.limitations()).anyMatch(l -> l.contains("capability not derivable"));
        }
    }

    @Nested
    class OperationalPerformance {

        @Test
        void latestOperatingEffectiveness_returnedAsOperationalPerformance() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            ControlEffectivenessAssessment assessment = makeAssessment(
                    control,
                    ControlEffectivenessRating.EFFECTIVE,
                    ControlEffectivenessRating.PARTIALLY_EFFECTIVE,
                    AS_OF_DATE);
            stubAssessments(List.of(assessment));
            // Fresh PASS test
            ControlTest freshPass = makeControlTest(control, ControlTestConclusion.EFFECTIVE, AS_OF_DATE.minusDays(10));
            stubTests(List.of(freshPass));

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.operationalPerformance()).isNotNull();
            assertThat(item.operationalPerformance().value()).isEqualTo("PARTIALLY_EFFECTIVE");
            assertThat(item.operationalPerformance().scale()).isEqualTo("ordinal");
            assertThat(item.limitations()).noneMatch(l -> l.contains("operational_performance not derivable"));
        }

        @Test
        void noAssessment_operationalPerformanceNullWithLimitation() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.operationalPerformance().value()).isNull();
            assertThat(item.limitations()).anyMatch(l -> l.contains("operational_performance not derivable"));
        }

        @Test
        void noFreshPassTests_addsFreshnesslimitation() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            ControlEffectivenessAssessment assessment = makeAssessment(
                    control, ControlEffectivenessRating.EFFECTIVE, ControlEffectivenessRating.EFFECTIVE, AS_OF_DATE);
            stubAssessments(List.of(assessment));
            // Stale test (outside freshness window)
            ControlTest staleTest =
                    makeControlTest(control, ControlTestConclusion.EFFECTIVE, AS_OF_DATE.minusDays(100));
            stubTests(List.of(staleTest));

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.limitations()).anyMatch(l -> l.contains("no fresh PASS tests"));
        }
    }

    @Nested
    class Coverage {

        @Test
        void countOfDistinctAnalysisEndpoints() {
            stubProject();
            // Two mappings for the same control to two different scenarios
            RiskControlMapping m1 = mappingWithInfluence(null, MappingControlRole.PREVENTIVE);
            RiskControlMapping m2 = mappingToScenario(control, UUID.randomUUID());
            stubMappings(List.of(m1, m2));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            // Both mappings are for the same control, so they're in one group
            // Coverage = count of distinct analysis endpoints for that control
            var item = result.controls().get(0);
            assertThat(item.coverage().scale()).isEqualTo("count");
            assertThat(item.coverage().units()).isEqualTo("endpoints");
            // Two distinct scenario endpoints were prepared, so coverage must be exactly 2 — a
            // regression that counted only the first endpoint (returning 1) must fail this.
            assertThat((Integer) item.coverage().value()).isEqualTo(2);
        }

        @Test
        void singleEndpoint_coverageIsOne() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat((Integer) item.coverage().value()).isEqualTo(1);
        }
    }

    @Nested
    class Effects {

        @Test
        void methodologyInfluence_dimensionKeys_returnedAsEffects() {
            stubProject();
            Map<String, Object> influence = new HashMap<>();
            influence.put("fair_cam_domain", "loss_event_control");
            influence.put("loss_event_frequency", 0.3);
            influence.put("loss_magnitude", 0.1);
            stubMappings(List.of(mappingWithInfluence(influence, MappingControlRole.PREVENTIVE)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.effects()).hasSize(2);
            assertThat(item.effects()).anyMatch(e -> e.dimension().jsonKey().equals("loss_event_frequency"));
            assertThat(item.effects()).anyMatch(e -> e.dimension().jsonKey().equals("loss_magnitude"));
        }

        @Test
        void noMethodologyInfluence_effectsEmptyWithLimitation() {
            stubProject();
            stubMappings(List.of(mappingWithInfluence(null, MappingControlRole.PREVENTIVE)));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            var item = result.controls().get(0);
            assertThat(item.effects()).isEmpty();
            assertThat(item.limitations()).anyMatch(l -> l.contains("effects not derivable"));
        }
    }

    @Nested
    class DomainFilter {

        @Test
        void domainFilter_onlyIncludesMatchingControls() {
            stubProject();
            // One LOSS_EVENT_CONTROL (PREVENTIVE), one DECISION_SUPPORT_CONTROL (DIRECTIVE)
            RiskControlMapping m1 = mappingWithInfluence(null, MappingControlRole.PREVENTIVE);
            Control ctrl2 = makeControl(project, "CTRL-2", "Second Control");
            RiskControlMapping m2 = mappingForControl(ctrl2, null, MappingControlRole.DIRECTIVE);
            stubMappings(List.of(m1, m2));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result = service.analyze(
                    PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, FairCamControlDomain.LOSS_EVENT_CONTROL);

            assertThat(result.controls()).hasSize(1);
            assertThat(result.controls().get(0).controlUid()).isEqualTo("CTRL-1");
        }
    }

    @Nested
    class ScopeFilters {

        @Test
        void multipleScopeFilters_intersect_ratherThanPrecedenceSelect() {
            stubProject();
            Control ctrl = makeControlWithId("CTRL-F", "Filtered Control", CONTROL_ID);
            UUID scenA = UUID.randomUUID();
            UUID scenB = UUID.randomUUID();
            RiskControlMapping mA = mappingControlToScenario(ctrl, scenA, null, MappingControlRole.PREVENTIVE);
            RiskControlMapping mB = mappingControlToScenario(ctrl, scenB, null, MappingControlRole.PREVENTIVE);
            // controlId is the candidate query; both scenario mappings come back from the DB.
            when(mappingRepo.findByProjectIdAndControlId(PROJECT_ID, CONTROL_ID))
                    .thenReturn(List.of(mA, mB));
            stubNoAssessments();
            stubNoTests();

            // Request controlId AND riskScenarioId=A: must intersect to only the A mapping,
            // not return every mapping of the control.
            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, CONTROL_ID, null, scenA, null, null, null, null);

            assertThat(result.controls()).hasSize(1);
            assertThat((Integer) result.controls().get(0).coverage().value()).isEqualTo(1);
        }
    }

    @Nested
    class MultiMappingContext {

        @Test
        void domainAndEffects_aggregatedAcrossAllMappings_notFirstOnly() {
            stubProject();
            Control ctrl = makeControlWithId("CTRL-M", "Multi-mapping Control", CONTROL_ID);
            Map<String, Object> infA = new HashMap<>();
            infA.put("fair_cam_domain", "loss_event_control");
            infA.put("loss_event_frequency", 0.3);
            Map<String, Object> infB = new HashMap<>();
            infB.put("fair_cam_domain", "decision_support_control");
            infB.put("decision_alignment", 0.5);
            RiskControlMapping mA =
                    mappingControlToScenario(ctrl, UUID.randomUUID(), infA, MappingControlRole.PREVENTIVE);
            RiskControlMapping mB =
                    mappingControlToScenario(ctrl, UUID.randomUUID(), infB, MappingControlRole.DIRECTIVE);
            stubMappings(List.of(mA, mB));
            stubNoAssessments();
            stubNoTests();

            FairCamControlAnalyticsResult result =
                    service.analyze(PROJECT_ID, AS_OF, 90, null, null, null, null, null, null, null);

            assertThat(result.controls()).hasSize(1);
            var item = result.controls().get(0);
            // Both mappings' domains surface, not just the first mapping's.
            assertThat(item.domainAttributions())
                    .extracting(FairCamControlAnalyticsResult.DomainAttribution::domain)
                    .containsExactlyInAnyOrder(
                            FairCamControlDomain.LOSS_EVENT_CONTROL, FairCamControlDomain.DECISION_SUPPORT_CONTROL);
            // Each attribution is tagged with the analysis endpoint it is contextual to.
            assertThat(item.domainAttributions())
                    .allMatch(da -> da.analysisEndpoint() != null
                            && da.analysisEndpoint().startsWith("RISK_SCENARIO:"));
            // Effects come from both mappings.
            assertThat(item.effects())
                    .extracting(FairCamControlAnalyticsResult.EffectEntry::dimension)
                    .contains(FairCamEffectDimension.LOSS_EVENT_FREQUENCY, FairCamEffectDimension.DECISION_ALIGNMENT);
            assertThat(item.effects()).allMatch(e -> e.analysisEndpoint() != null);
            // byDomain counts every domain the control is attributed to.
            assertThat(result.counts().byDomain()).containsKeys("loss_event_control", "decision_support_control");
        }

        @Test
        void domainFilter_matchesAnyMapping_notJustFirst() {
            stubProject();
            Control ctrl = makeControlWithId("CTRL-M2", "Multi-mapping Control 2", CONTROL_ID);
            Map<String, Object> infFirst = new HashMap<>();
            infFirst.put("fair_cam_domain", "loss_event_control");
            Map<String, Object> infSecond = new HashMap<>();
            infSecond.put("fair_cam_domain", "decision_support_control");
            RiskControlMapping mFirst =
                    mappingControlToScenario(ctrl, UUID.randomUUID(), infFirst, MappingControlRole.PREVENTIVE);
            RiskControlMapping mSecond =
                    mappingControlToScenario(ctrl, UUID.randomUUID(), infSecond, MappingControlRole.DIRECTIVE);
            stubMappings(List.of(mFirst, mSecond));
            stubNoAssessments();
            stubNoTests();

            // Filter on the SECOND mapping's domain — the control must still be kept.
            FairCamControlAnalyticsResult result = service.analyze(
                    PROJECT_ID,
                    AS_OF,
                    90,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    FairCamControlDomain.DECISION_SUPPORT_CONTROL);

            assertThat(result.controls()).hasSize(1);
            assertThat(result.controls().get(0).controlUid()).isEqualTo("CTRL-M2");
        }
    }

    // ---- Enum tests ----

    @Test
    void fairCamControlDomain_fromJsonKey_roundTrips() {
        for (FairCamControlDomain domain : FairCamControlDomain.values()) {
            assertThat(FairCamControlDomain.fromJsonKey(domain.jsonKey())).isEqualTo(domain);
        }
    }

    @Test
    void fairCamControlDomain_fromJsonKey_nullAndBlank_returnsNull() {
        assertThat(FairCamControlDomain.fromJsonKey(null)).isNull();
        assertThat(FairCamControlDomain.fromJsonKey("")).isNull();
        assertThat(FairCamControlDomain.fromJsonKey("unknown_domain")).isNull();
    }

    @Test
    void fairCamEffectDimension_fromJsonKey_roundTrips() {
        for (FairCamEffectDimension dim : FairCamEffectDimension.values()) {
            assertThat(FairCamEffectDimension.fromJsonKey(dim.jsonKey())).isEqualTo(dim);
        }
    }

    // ---- Helpers ----

    private void stubProject() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
    }

    private void stubNoMappings() {
        when(mappingRepo.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());
    }

    private void stubMappings(List<RiskControlMapping> mappings) {
        when(mappingRepo.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(mappings);
    }

    private void stubNoAssessments() {
        when(assessmentRepo.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        eq(PROJECT_ID), any()))
                .thenReturn(List.of());
    }

    private void stubAssessments(List<ControlEffectivenessAssessment> assessments) {
        when(assessmentRepo.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        eq(PROJECT_ID), any()))
                .thenReturn(assessments);
    }

    private void stubNoTests() {
        when(testRepo.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(eq(PROJECT_ID), any()))
                .thenReturn(List.of());
    }

    private void stubTests(List<ControlTest> tests) {
        when(testRepo.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(eq(PROJECT_ID), any()))
                .thenReturn(tests);
    }

    private Project makeProject() {
        var p = new Project("ground-control", "Ground Control");
        setId(p, PROJECT_ID);
        return p;
    }

    private Control makeControl(Project project, String uid, String title) {
        var c = new Control(project, uid, title, ControlFunction.PREVENTIVE);
        setId(c, UUID.randomUUID());
        return c;
    }

    private Control makeControlWithId(String uid, String title, UUID id) {
        var c = new Control(project, uid, title, ControlFunction.PREVENTIVE);
        setId(c, id);
        return c;
    }

    private RiskControlMapping mappingControlToScenario(
            Control ctrl, UUID scenarioId, Map<String, Object> influence, MappingControlRole role) {
        var scenario = new RiskScenario(project, "RS-" + scenarioId, "Scenario " + scenarioId, "t", "m", "a", "e");
        setId(scenario, scenarioId);
        RiskControlMapping m = RiskControlMapping.forControlScenario(project, ctrl, scenario, role);
        setId(m, UUID.randomUUID());
        m.setMethodologyInfluence(influence);
        return m;
    }

    private ControlEffectivenessAssessment makeAssessment(
            Control control,
            ControlEffectivenessRating design,
            ControlEffectivenessRating operating,
            LocalDate assessedAt) {
        var a = new ControlEffectivenessAssessment(
                project, control, "ASSESS-1", design, operating, assessedAt, "analyst@test");
        setId(a, UUID.randomUUID());
        return a;
    }

    private ControlTest makeControlTest(Control ctrl, ControlTestConclusion conclusion, LocalDate testDate) {
        var t = new ControlTest(
                project, ctrl, "TEST-1", ControlTestMethodology.INQUIRY, conclusion, "tester@test", testDate);
        t.setTestSteps("steps");
        t.setExpectedResults("expected");
        t.setActualResults("actual");
        setId(t, UUID.randomUUID());
        return t;
    }

    private RiskControlMapping mappingWithInfluence(Map<String, Object> influence, MappingControlRole role) {
        RiskScenario scenario = makeScenario();
        RiskControlMapping m = RiskControlMapping.forControlScenario(project, control, scenario, role);
        setId(m, UUID.randomUUID());
        m.setMethodologyInfluence(influence);
        return m;
    }

    private RiskControlMapping mappingToScenario(Control ctrl, UUID scenarioId) {
        RiskScenario scenario = makeScenario();
        setId(scenario, scenarioId);
        RiskControlMapping m =
                RiskControlMapping.forControlScenario(project, ctrl, scenario, MappingControlRole.DETECTIVE);
        setId(m, UUID.randomUUID());
        return m;
    }

    private RiskControlMapping mappingForControl(Control ctrl, Map<String, Object> influence, MappingControlRole role) {
        RiskScenario scenario = makeScenario();
        RiskControlMapping m = RiskControlMapping.forControlScenario(project, ctrl, scenario, role);
        setId(m, UUID.randomUUID());
        m.setMethodologyInfluence(influence);
        return m;
    }

    private RiskScenario makeScenario() {
        var scenario = new RiskScenario(project, "RS-TEST", "Test Scenario", "threat", "method", "asset", "effect");
        setId(scenario, SCENARIO_ID);
        return scenario;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> cls = entity.getClass();
            // Walk up the class hierarchy to find the id field
            while (cls != null) {
                try {
                    var field = cls.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            throw new RuntimeException("No id field found on " + entity.getClass());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot set id on " + entity.getClass(), e);
        }
    }
}
