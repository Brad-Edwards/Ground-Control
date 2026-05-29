package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatEventKind;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatSourceRelevance;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NistAssessmentServiceTest {

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private NistAssessmentService service;

    private Project project;
    private UUID projectId;
    private RiskScenario scenario;
    private MethodologyProfile nistProfile;
    private MethodologyProfile fairProfile;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        scenario = new RiskScenario(project, "RS-001", "Scenario", "threat", "method", "asset", "effect");
        setField(scenario, "id", UUID.randomUUID());

        nistProfile = new MethodologyProfile(
                project, "NIST_SP800_30_R1", "NIST SP 800-30 Rev. 1", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());

        fairProfile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        setField(fairProfile, "id", UUID.randomUUID());
    }

    private RiskAssessmentResult makeAssessment(MethodologyProfile profile, Map<String, Object> inputs) {
        var result = new RiskAssessmentResult(project, scenario, profile);
        setField(result, "id", UUID.randomUUID());
        result.setInputFactors(inputs);
        result.setAssessmentAt(Instant.parse("2026-05-29T00:00:00Z"));
        result.setTimeHorizon("12 months");
        return result;
    }

    private Map<String, Object> adversarialInputs(
            NistLikelihoodBand initiation, NistLikelihoodBand adverseImpact, NistImpactBand impact) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threat_source", Map.of("id", "TS-1", "name", "External attacker", "kind", "ADVERSARIAL"));
        m.put("threat_event", Map.of("id", "TE-1", "description", "Phishing", "kind", "ADVERSARIAL"));
        m.put("threat_event_kind", "ADVERSARIAL");
        m.put("threat_source_characteristics", Map.of("capability", "HIGH", "intent", "HIGH", "targeting", "MODERATE"));
        m.put("threat_source_relevance", "EXPECTED");
        m.put("vulnerabilities", List.of(Map.of("id", "V-1", "description", "Weak MFA", "severity", "MODERATE")));
        m.put(
                "predisposing_conditions",
                List.of(Map.of("id", "PC-1", "description", "Remote workforce", "pervasiveness", "HIGH")));
        m.put("likelihood_initiation", initiation.name());
        m.put("likelihood_adverse_impact", adverseImpact.name());
        m.put("impact_level", impact.name());
        m.put("assessment_timeframe", Map.of("from", "2026-01-01", "to", "2026-12-31"));
        return m;
    }

    @Test
    void analyze_emptyProject_returnsZeroAssessments() {
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        NistAssessmentResult result = service.analyze(projectId, Instant.parse("2026-05-29T00:00:00Z"), null, null);

        assertThat(result.analysisKind()).isEqualTo("nist_assessment");
        assertThat(result.project()).isEqualTo("ground-control");
        assertThat(result.derivationMethod()).isEqualTo("nist-sp800-30-rev1-5x5-matrix-v1");
        assertThat(result.scale()).isEqualTo("ordinal");
        assertThat(result.units()).isEqualTo("qualitative ordinal levels");
        assertThat(result.matrixConversionRule()).contains("NIST SP 800-30 Rev. 1");
        assertThat(result.assessments()).isEmpty();
        assertThat(result.counts().total()).isZero();
        assertThat(result.counts().withLimitations()).isZero();
    }

    @Test
    void analyze_skipsNonNistProfiles() {
        var fairResult = makeAssessment(fairProfile, Map.of("annualized_loss_expectancy", 12345));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(fairResult));

        NistAssessmentResult result = service.analyze(projectId, Instant.parse("2026-05-29T00:00:00Z"), null, null);

        assertThat(result.assessments()).isEmpty();
    }

    @Test
    void analyze_adversarialEvent_populatesAllStructuredFields() {
        var nistResult = makeAssessment(
                nistProfile,
                adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.MODERATE, NistImpactBand.HIGH));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, Instant.parse("2026-05-29T00:00:00Z"), null, null);

        assertThat(result.assessments()).hasSize(1);
        var item = result.assessments().get(0);
        assertThat(item.profileKey()).isEqualTo("NIST_SP800_30_R1");
        assertThat(item.family()).isEqualTo("NIST_SP800_30_R1");
        assertThat(item.version()).isEqualTo("1");
        assertThat(item.inputs().threatEventKind()).isEqualTo(ThreatEventKind.ADVERSARIAL);
        assertThat(item.inputs().threatSourceRelevance()).isEqualTo(ThreatSourceRelevance.EXPECTED);
        assertThat(item.inputs().likelihoodInitiation()).isEqualTo(NistLikelihoodBand.HIGH);
        assertThat(item.inputs().likelihoodAdverseImpact()).isEqualTo(NistLikelihoodBand.MODERATE);
        assertThat(item.inputs().impactLevel()).isEqualTo(NistImpactBand.HIGH);
        assertThat(item.inputs().vulnerabilities()).hasSize(1);
        assertThat(item.inputs().predisposingConditions()).hasSize(1);
        // NIST SP 800-30 Rev. 1 Table I-2:
        //   overall = min(initiation=HIGH, adverseImpact=MODERATE) = MODERATE (ordinal 2)
        //   impact  = HIGH (ordinal 3)
        //   Table I-2 Moderate likelihood × High impact → Moderate risk
        //   matrix cell label uses 1-indexed ordinals → "L3-I4"
        assertThat(item.outputs().overallLikelihood()).isEqualTo(NistLikelihoodBand.MODERATE);
        assertThat(item.outputs().impactLevel()).isEqualTo(NistImpactBand.HIGH);
        assertThat(item.outputs().matrixCell()).isEqualTo("L3-I4");
        assertThat(item.outputs().riskLevel()).isEqualTo("MODERATE");
        assertThat(item.timeHorizon()).isEqualTo("12 months");
    }

    @Test
    void analyze_overallLikelihoodAbsent_derivesFromInitiationAndAdverseImpact() {
        var inputs = adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.LOW, NistImpactBand.MODERATE);
        // likelihood_overall is deliberately absent; service derives it
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // NIST SP 800-30 Rev. 1 Table G-5: overall = min(initiation, adverse_impact)
        assertThat(item.inputs().likelihoodOverall()).isEqualTo(NistLikelihoodBand.LOW);
        assertThat(item.outputs().overallLikelihood()).isEqualTo(NistLikelihoodBand.LOW);
        assertThat(item.outputs().derivation()).contains("Table G-5");
    }

    @Test
    void analyze_overallLikelihoodSupplied_takesPrecedenceOverDerivation() {
        var inputs = adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.LOW, NistImpactBand.MODERATE);
        inputs.put("likelihood_overall", "HIGH");
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.inputs().likelihoodOverall()).isEqualTo(NistLikelihoodBand.HIGH);
        assertThat(item.outputs().overallLikelihood()).isEqualTo(NistLikelihoodBand.HIGH);
        assertThat(item.outputs().derivation()).contains("analyst-supplied");
    }

    @Test
    void analyze_nonAdversarialEvent_doesNotRequireCapabilityIntentTargeting() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_source", Map.of("id", "TS-2", "name", "Power outage", "kind", "ENVIRONMENTAL"));
        inputs.put(
                "threat_event",
                Map.of("id", "TE-2", "description", "Datacenter power loss", "kind", "NON_ADVERSARIAL"));
        inputs.put("threat_event_kind", "NON_ADVERSARIAL");
        inputs.put("threat_source_relevance", "ANTICIPATED");
        inputs.put("likelihood_initiation", "MODERATE");
        inputs.put("likelihood_adverse_impact", "HIGH");
        inputs.put("impact_level", "HIGH");
        // no capability/intent/targeting; predisposing_conditions intentionally empty
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.inputs().threatEventKind()).isEqualTo(ThreatEventKind.NON_ADVERSARIAL);
        assertThat(item.outputs().riskLevel()).isNotBlank();
        // Limitation should be raised because predisposing conditions are absent
        assertThat(item.limitations()).anyMatch(s -> s.toLowerCase().contains("predisposing"));
    }

    @Test
    void analyze_nonAdversarialEvent_withAdversarialOnlyFields_emitsLimitation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_source", Map.of("id", "TS-3", "kind", "ENVIRONMENTAL"));
        inputs.put("threat_event", Map.of("id", "TE-3", "kind", "NON_ADVERSARIAL"));
        inputs.put("threat_event_kind", "NON_ADVERSARIAL");
        // Adversarial-only fields shouldn't apply but are present
        inputs.put("threat_source_characteristics", Map.of("capability", "HIGH"));
        inputs.put("threat_source_relevance", "POSSIBLE");
        inputs.put("likelihood_initiation", "LOW");
        inputs.put("likelihood_adverse_impact", "LOW");
        inputs.put("impact_level", "LOW");
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.toLowerCase().contains("adversarial-only"));
    }

    @Test
    void analyze_filterByRiskScenarioId_callsScenarioScopedQuery() {
        var nistResult = makeAssessment(
                nistProfile, adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.HIGH, NistImpactBand.HIGH));
        when(riskAssessmentResultRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(
                        projectId, scenario.getId()))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, scenario.getId());

        assertThat(result.assessments()).hasSize(1);
    }

    @Test
    void analyze_filterByAssessmentId_returnsSingleRowOrEmpty() {
        var nistResult = makeAssessment(
                nistProfile, adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.HIGH, NistImpactBand.HIGH));
        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(nistResult.getId(), projectId))
                .thenReturn(Optional.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, nistResult.getId(), null);

        assertThat(result.assessments()).hasSize(1);
        assertThat(result.assessments().get(0).assessmentId()).isEqualTo(nistResult.getId());
    }

    @Test
    void analyze_filterByAssessmentId_nonNistProfile_throwsValidationError() {
        var fairResult = makeAssessment(fairProfile, Map.of("annualized_loss_expectancy", 12345));
        UUID fairId = fairResult.getId();
        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(fairId, projectId))
                .thenReturn(Optional.of(fairResult));

        assertThatThrownBy(() -> service.analyze(projectId, null, fairId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("NIST_SP800_30_R1");
    }

    @Test
    void analyze_filterByAssessmentId_notFound_throwsNotFound() {
        UUID missing = UUID.randomUUID();
        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(missing, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(projectId, null, missing, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void analyze_invalidEnumValue_emitsLimitationNotCrash() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_kind", "BOGUS");
        inputs.put("threat_source_relevance", "NOT_A_VALUE");
        inputs.put("likelihood_initiation", "MEH");
        inputs.put("likelihood_adverse_impact", "LOW");
        inputs.put("impact_level", "HIGH");
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).isNotEmpty();
    }

    // NIST SP 800-30 Rev. 1 Table I-2 exhaustive coverage. Every cell of the
    // 5x5 risk matrix must be exercised so a transposed row/column or wrong
    // cell value would surface here rather than at runtime against real data.
    // Inputs supply likelihood_overall directly so the service uses it
    // verbatim (no Table G-5 derivation) and the assertion exercises only
    // riskLevelFrom(overall, impact).
    @ParameterizedTest(name = "L={0} I={1} → {2} ({3})")
    @CsvSource({
        "VERY_LOW,VERY_LOW,VERY_LOW,L1-I1",
        "VERY_LOW,LOW,VERY_LOW,L1-I2",
        "VERY_LOW,MODERATE,VERY_LOW,L1-I3",
        "VERY_LOW,HIGH,LOW,L1-I4",
        "VERY_LOW,VERY_HIGH,LOW,L1-I5",
        "LOW,VERY_LOW,VERY_LOW,L2-I1",
        "LOW,LOW,LOW,L2-I2",
        "LOW,MODERATE,LOW,L2-I3",
        "LOW,HIGH,LOW,L2-I4",
        "LOW,VERY_HIGH,MODERATE,L2-I5",
        "MODERATE,VERY_LOW,VERY_LOW,L3-I1",
        "MODERATE,LOW,LOW,L3-I2",
        "MODERATE,MODERATE,MODERATE,L3-I3",
        "MODERATE,HIGH,MODERATE,L3-I4",
        "MODERATE,VERY_HIGH,HIGH,L3-I5",
        "HIGH,VERY_LOW,LOW,L4-I1",
        "HIGH,LOW,MODERATE,L4-I2",
        "HIGH,MODERATE,MODERATE,L4-I3",
        "HIGH,HIGH,HIGH,L4-I4",
        "HIGH,VERY_HIGH,VERY_HIGH,L4-I5",
        "VERY_HIGH,VERY_LOW,LOW,L5-I1",
        "VERY_HIGH,LOW,MODERATE,L5-I2",
        "VERY_HIGH,MODERATE,HIGH,L5-I3",
        "VERY_HIGH,HIGH,VERY_HIGH,L5-I4",
        "VERY_HIGH,VERY_HIGH,VERY_HIGH,L5-I5",
    })
    void analyze_table_I_2_matrixCoverage(
            NistLikelihoodBand overall, NistImpactBand impact, String expectedRisk, String expectedCell) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_kind", "ADVERSARIAL");
        inputs.put("threat_source_relevance", "EXPECTED");
        inputs.put(
                "predisposing_conditions",
                List.of(Map.of("id", "PC-1", "description", "X", "pervasiveness", "MODERATE")));
        inputs.put("likelihood_overall", overall.name());
        inputs.put("impact_level", impact.name());
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().riskLevel()).isEqualTo(expectedRisk);
        assertThat(item.outputs().matrixCell()).isEqualTo(expectedCell);
    }

    @Test
    void analyze_persistedComputedOutputs_takePrecedenceOverDerivation() {
        // Regression for codex finding #1: the NIST view must not diverge from
        // the durable RiskAssessmentResult. When computedOutputs carries
        // analyst-approved values, the read view echoes them rather than
        // recomputing from inputs.
        var inputs = adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.HIGH, NistImpactBand.HIGH);
        var nistResult = makeAssessment(nistProfile, inputs);
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("overall_likelihood", "LOW");
        persisted.put("impact_level", "MODERATE");
        persisted.put("risk_level", "LOW");
        persisted.put("matrix_cell", "L2-I3");
        persisted.put("derivation", "analyst-overridden after committee review");
        nistResult.setComputedOutputs(persisted);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().overallLikelihood()).isEqualTo(NistLikelihoodBand.LOW);
        assertThat(item.outputs().impactLevel()).isEqualTo(NistImpactBand.MODERATE);
        assertThat(item.outputs().riskLevel()).isEqualTo("LOW");
        assertThat(item.outputs().matrixCell()).isEqualTo("L2-I3");
        assertThat(item.outputs().derivation()).contains("persisted");
        assertThat(item.outputs().derivation()).contains("analyst-overridden after committee review");
    }

    @Test
    void analyze_invalidLikelihoodOverall_emitsLimitationDoesNotThrow() {
        // Regression for codex finding #2: an invalid likelihood_overall value
        // must NOT throw UnsupportedOperationException on List.of().add(...).
        // It must record a limitation and let derivation fall through to the
        // initiation × adverse-impact derivation per Table G-5.
        var inputs = adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.MODERATE, NistImpactBand.HIGH);
        inputs.put("likelihood_overall", "NOT_A_VALID_BAND");
        var nistResult = makeAssessment(nistProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.contains("likelihood_overall") && s.contains("NOT_A_VALID_BAND"));
        // Derivation should fall through to the Table G-5 path
        assertThat(item.outputs().overallLikelihood()).isEqualTo(NistLikelihoodBand.MODERATE);
        assertThat(item.outputs().derivation()).contains("Table G-5");
    }

    @Test
    void analyze_counts_groupAssessmentsByRiskLevel() {
        var high = makeAssessment(
                nistProfile, adversarialInputs(NistLikelihoodBand.HIGH, NistLikelihoodBand.HIGH, NistImpactBand.HIGH));
        var low = makeAssessment(
                nistProfile, adversarialInputs(NistLikelihoodBand.LOW, NistLikelihoodBand.LOW, NistImpactBand.LOW));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(high, low));

        NistAssessmentResult result = service.analyze(projectId, null, null, null);

        assertThat(result.counts().total()).isEqualTo(2);
        assertThat(result.counts().byRiskLevel()).isNotEmpty();
        int sum = result.counts().byRiskLevel().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        assertThat(sum).isEqualTo(2);
    }

    @Test
    void analyze_asOfDefaultsToNow_whenNull() {
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        Instant before = Instant.now().minusSeconds(2);
        NistAssessmentResult result = service.analyze(projectId, null, null, null);
        Instant after = Instant.now().plusSeconds(2);

        assertThat(result.asOf()).isBetween(before, after);
    }
}
