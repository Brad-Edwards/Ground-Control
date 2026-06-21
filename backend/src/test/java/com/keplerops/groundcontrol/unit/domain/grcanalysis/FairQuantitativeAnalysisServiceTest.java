package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairFormOfLoss;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FairQuantitativeAnalysisServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Spy
    private Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @InjectMocks
    private FairQuantitativeAnalysisService service;

    private Project project;
    private UUID projectId;
    private RiskScenario scenario;
    private MethodologyProfile fairProfile;
    private MethodologyProfile nistProfile;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        scenario = new RiskScenario(project, "RS-001", "Scenario", "threat", "method", "asset", "effect");
        setField(scenario, "id", UUID.randomUUID());

        fairProfile = new MethodologyProfile(
                project, "FAIR_V3_0", "Open FAIR", "O-RT 3.0.1 / O-RA 2.0.1", MethodologyFamily.FAIR);
        setField(fairProfile, "id", UUID.randomUUID());

        nistProfile = new MethodologyProfile(
                project, "NIST_SP800_30_R1", "NIST SP 800-30 Rev. 1", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());
    }

    private RiskAssessmentResult makeAssessment(MethodologyProfile profile, Map<String, Object> inputs) {
        var result = new RiskAssessmentResult(project, scenario, profile);
        setField(result, "id", UUID.randomUUID());
        result.setInputFactors(inputs);
        result.setAssessmentAt(Instant.parse("2026-05-29T00:00:00Z"));
        result.setTimeHorizon("12 months");
        return result;
    }

    private Map<String, Object> threePoint(double low, double likely, double high) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("low", low);
        m.put("likely", likely);
        m.put("high", high);
        return m;
    }

    private Map<String, Object> threePointWithCurrency(double low, double likely, double high, String currency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("low", low);
        m.put("likely", likely);
        m.put("high", high);
        m.put("currency", currency);
        return m;
    }

    @Test
    void analyze_emptyProject_returnsZeroAssessments() {
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        FairQuantitativeAnalysisResult result =
                service.analyze(projectId, Instant.parse("2026-05-29T00:00:00Z"), null, null);

        assertThat(result.analysisKind()).isEqualTo("fair_quantitative");
        assertThat(result.project()).isEqualTo("ground-control");
        assertThat(result.derivationMethod()).isEqualTo("open-fair-o-rt3.0.1-o-ra2.0.1-three-point-v1");
        assertThat(result.scale()).isEqualTo("continuous");
        assertThat(result.units()).isEqualTo("monetary");
        assertThat(result.assessments()).isEmpty();
        assertThat(result.counts().total()).isZero();
        assertThat(result.counts().withLimitations()).isZero();
    }

    @Test
    void analyze_skipsNonFairProfiles() {
        var nistResult = makeAssessment(nistProfile, Map.of("likelihood_initiation", "HIGH"));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(nistResult));

        FairQuantitativeAnalysisResult result =
                service.analyze(projectId, Instant.parse("2026-05-29T00:00:00Z"), null, null);

        assertThat(result.assessments()).isEmpty();
    }

    @Test
    void analyze_basicDerivation_LEF_LM_ALE() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments()).hasSize(1);
        var item = result.assessments().get(0);
        // LEF = TEF * Vuln elementwise: low=1*0.1=0.1, likely=2*0.2=0.4, high=4*0.4=1.6
        assertThat(item.outputs().lossEventFrequency().low())
                .isEqualTo(0.1, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(item.outputs().lossEventFrequency().likely())
                .isEqualTo(0.4, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(item.outputs().lossEventFrequency().high())
                .isEqualTo(1.6, org.assertj.core.api.Assertions.within(1e-9));
        // LM = PLM (no secondary): low=1000, likely=5000, high=20000
        assertThat(item.outputs().lossMagnitude().low()).isEqualTo(1000.0);
        assertThat(item.outputs().lossMagnitude().likely()).isEqualTo(5000.0);
        assertThat(item.outputs().lossMagnitude().high()).isEqualTo(20000.0);
        // ALE = LEF * LM: low=0.1*1000=100, likely=0.4*5000=2000, high=1.6*20000=32000
        assertThat(item.outputs().annualizedLossExpectancy().low())
                .isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().annualizedLossExpectancy().likely())
                .isEqualTo(2000.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().annualizedLossExpectancy().high())
                .isEqualTo(32000.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().derivation()).contains("derived");
    }

    @Test
    void analyze_persistedComputedOutputs_win() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);

        Map<String, Object> persistedLef = new LinkedHashMap<>();
        persistedLef.put("low", 0.5);
        persistedLef.put("likely", 0.6);
        persistedLef.put("high", 0.7);

        Map<String, Object> persistedAle = new LinkedHashMap<>();
        persistedAle.put("low", 500.0);
        persistedAle.put("likely", 3000.0);
        persistedAle.put("high", 14000.0);
        persistedAle.put("currency", "USD");

        Map<String, Object> computedOutputs = new LinkedHashMap<>();
        computedOutputs.put("loss_event_frequency", persistedLef);
        computedOutputs.put("annualized_loss_expectancy", persistedAle);
        assessment.setComputedOutputs(computedOutputs);

        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // Persisted LEF should win over derived
        assertThat(item.outputs().lossEventFrequency().low()).isEqualTo(0.5);
        assertThat(item.outputs().lossEventFrequency().likely()).isEqualTo(0.6);
        assertThat(item.outputs().lossEventFrequency().high()).isEqualTo(0.7);
        // Persisted ALE should win
        assertThat(item.outputs().annualizedLossExpectancy().low()).isEqualTo(500.0);
        assertThat(item.outputs().annualizedLossExpectancy().likely()).isEqualTo(3000.0);
        assertThat(item.outputs().annualizedLossExpectancy().high()).isEqualTo(14000.0);
        assertThat(item.outputs().derivation()).contains("persisted");
    }

    @Test
    void analyze_analystSuppliedLEF_usedWhenNoTEFVuln() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        // Only LEF supplied (no TEF or Vuln)
        inputs.put("loss_event_frequency", threePoint(0.3, 0.5, 0.9));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(2000.0, 8000.0, 30000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().lossEventFrequency().low()).isEqualTo(0.3);
        assertThat(item.outputs().lossEventFrequency().likely()).isEqualTo(0.5);
        assertThat(item.outputs().lossEventFrequency().high()).isEqualTo(0.9);
        assertThat(item.outputs().derivation()).contains("analyst-supplied");
    }

    @Test
    void analyze_percentilePreservation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);

        Map<String, Object> percentiles = new LinkedHashMap<>();
        percentiles.put("p10", 500.0);
        percentiles.put("p50", 3000.0);
        percentiles.put("p90", 12000.0);
        percentiles.put("p95", 18000.0);

        Map<String, Object> persistedAle = new LinkedHashMap<>();
        persistedAle.put("low", 500.0);
        persistedAle.put("likely", 3000.0);
        persistedAle.put("high", 12000.0);
        persistedAle.put("currency", "USD");
        persistedAle.put("percentiles", percentiles);

        Map<String, Object> computedOutputs = new LinkedHashMap<>();
        computedOutputs.put("annualized_loss_expectancy", persistedAle);
        assessment.setComputedOutputs(computedOutputs);

        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().percentiles()).isNotNull();
        assertThat(item.outputs().percentiles()).containsKey("p10");
        assertThat(item.outputs().percentiles()).containsKey("p50");
        assertThat(item.outputs().percentiles()).containsKey("p90");
        assertThat(item.outputs().percentiles()).containsKey("p95");
    }

    @Test
    void analyze_currencyPreservation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "EUR"));
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        assertThat(result.currency()).isEqualTo("EUR");
        var item = result.assessments().get(0);
        assertThat(item.outputs().currency()).isEqualTo("EUR");
    }

    @Test
    void analyze_mixedCurrencies_emitsLimitation_andDoesNotSumAcrossCurrencies() {
        // PLM=USD, SLM=EUR — mixing currencies is an invariant breach for LM derivation.
        // The limitation must be emitted AND the cross-currency EUR contribution must NOT
        // be added into the USD loss magnitude.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("secondary_loss_event_frequency", threePoint(0.5, 0.7, 0.9));
        inputs.put("secondary_loss_magnitude", threePointWithCurrency(500.0, 2000.0, 8000.0, "EUR"));
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.toLowerCase().contains("mixed currencies"));
        // LM must equal PLM only — EUR secondary loss must NOT be added in
        assertThat(item.outputs().lossMagnitude()).isNotNull();
        assertThat(item.outputs().lossMagnitude().low())
                .isEqualTo(1000.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().lossMagnitude().likely())
                .isEqualTo(5000.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().lossMagnitude().high())
                .isEqualTo(20000.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    // ---- FAIR invariant enforcement tests (TDD: these must fail before the fix) ----

    @Test
    void analyze_vulnerabilityOutOfBounds_nonDerivable_noAle() {
        // vulnerability > 1 violates the FAIR [0,1] probability bound.
        // The assessment must be marked non-derivable: no fabricated ALE, limitation present.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        // vulnerability.high = 1.4 — out of [0,1]
        inputs.put("vulnerability", threePoint(0.1, 0.2, 1.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // Limitation must name the violated factor and bound
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("vulnerability")
                        && (s.toLowerCase().contains("0..1")
                                || s.toLowerCase().contains("[0,1]")
                                || s.toLowerCase().contains("out of")
                                || s.toLowerCase().contains("non-derivable")));
        // No fabricated ALE — the service must not produce authoritative monetary output from invalid inputs
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_probabilityOfActionOutOfBounds_emitsLimitation() {
        // Probability of Action is a probability sub-factor, not an unbounded frequency.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("contact_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("probability_of_action", threePoint(0.2, 0.8, 1.2));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.contains("probability_of_action") && s.contains("[0,1]"));
    }

    @Test
    void analyze_threePointOutOfOrder_plm_nonDerivable() {
        // PLM low > high violates the three-point ordering invariant.
        // LM and ALE must not be produced; limitation must be emitted.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        // low=20000 > high=1000 — out of order
        inputs.put("primary_loss_magnitude", threePointWithCurrency(20000.0, 5000.0, 1000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("primary_loss_magnitude")
                        && (s.toLowerCase().contains("order")
                                || s.toLowerCase().contains("non-derivable")
                                || s.toLowerCase().contains("low")
                                || s.toLowerCase().contains("high")));
        assertThat(item.outputs().lossMagnitude()).isNull();
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_negativeFrequency_nonDerivable() {
        // Negative TEF value violates the non-negativity invariant for frequency factors.
        // LEF and ALE must not be derived; limitation must be emitted.
        Map<String, Object> inputs = new LinkedHashMap<>();
        // tef.low = -1 — negative frequency is invalid
        inputs.put("threat_event_frequency", threePoint(-1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("threat_event_frequency")
                        && (s.toLowerCase().contains("negative")
                                || s.toLowerCase().contains("non-negative")
                                || s.toLowerCase().contains("non-derivable")
                                || s.toLowerCase().contains("< 0")));
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_negativeLoss_nonDerivable() {
        // Negative PLM value violates the non-negativity invariant for loss-magnitude factors.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        // plm.low = -500 — negative loss is invalid
        inputs.put("primary_loss_magnitude", threePointWithCurrency(-500.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("primary_loss_magnitude")
                        && (s.toLowerCase().contains("negative")
                                || s.toLowerCase().contains("non-negative")
                                || s.toLowerCase().contains("non-derivable")
                                || s.toLowerCase().contains("< 0")));
        assertThat(item.outputs().lossMagnitude()).isNull();
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_validInputs_happyPath_stillDerivesCorrectly() {
        // All invariants satisfied — derivation must proceed normally (regression guard).
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // No invariant violations
        assertThat(item.limitations()).noneMatch(s -> s.toLowerCase().contains("non-derivable"));
        assertThat(item.outputs().annualizedLossExpectancy()).isNotNull();
        assertThat(item.outputs().annualizedLossExpectancy().low())
                .isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_secondaryLosses_addedToMagnitude() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("secondary_loss_event_frequency", threePoint(0.5, 0.7, 0.9));
        inputs.put("secondary_loss_magnitude", threePointWithCurrency(500.0, 2000.0, 8000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // LM = PLM + SLEF*SLM elementwise
        // low:   1000 + 0.5*500   = 1250
        // likely: 5000 + 0.7*2000 = 6400
        // high: 20000 + 0.9*8000  = 27200
        assertThat(item.outputs().lossMagnitude().low())
                .isEqualTo(1250.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().lossMagnitude().likely())
                .isEqualTo(6400.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().lossMagnitude().high())
                .isEqualTo(27200.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_missingTEF_notDerivable_emitsLimitation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        // No TEF, no analyst LEF, no persisted LEF
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("not-derivable")
                        || s.toLowerCase().contains("missing"));
        // No fabricated ALE — the service must not produce authoritative monetary output when LEF
        // is not derivable (missing TEF with no analyst-supplied or persisted LEF fallback)
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_missingVuln_notDerivable_emitsLimitation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        // No vulnerability, no analyst LEF
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("not-derivable")
                        || s.toLowerCase().contains("missing"));
        // No fabricated ALE — the service must not produce authoritative monetary output when LEF
        // is not derivable (TEF present but vulnerability missing with no analyst-supplied LEF)
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_missingPLM_emitsLimitation() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        // No PLM
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("primary_loss_magnitude")
                        || s.toLowerCase().contains("not-derivable"));
        // No fabricated LM or ALE — the service must not produce authoritative monetary output
        // when PLM is absent (loss magnitude is not derivable without a loss input)
        assertThat(item.outputs().lossMagnitude()).isNull();
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_directTefWithoutCfPoa_isValidAtHigherAbstraction() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .noneMatch(s -> s.contains("contact_frequency") && s.contains("probability_of_action"));
        assertThat(item.outputs().annualizedLossExpectancy()).isNotNull();
    }

    @Test
    void analyze_directVulnerabilityWithoutTcapRs_isValidAtHigherAbstraction() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .noneMatch(s -> s.contains("threat_capability") && s.contains("resistance_strength"));
        assertThat(item.outputs().annualizedLossExpectancy()).isNotNull();
    }

    @Test
    void analyze_derivesTefFromCfAndPoa_whenTefAbsent() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("contact_frequency", threePoint(10.0, 20.0, 40.0));
        inputs.put("probability_of_action", threePoint(0.1, 0.2, 0.5));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().lossEventFrequency().low())
                .isEqualTo(0.1, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(item.outputs().lossEventFrequency().likely())
                .isEqualTo(0.8, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(item.outputs().lossEventFrequency().high())
                .isEqualTo(8.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(item.outputs().derivation())
                .isEqualTo("derived: LEF = (Contact Frequency × Probability of Action) × Vulnerability");
    }

    @Test
    void analyze_tcapAndRsUsePercentileScale_notProbabilityScale() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("threat_capability", threePoint(25.0, 50.0, 90.0));
        inputs.put("resistance_strength", threePoint(20.0, 40.0, 80.0));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).noneMatch(s -> s.contains("threat_capability") && s.contains("[0,1]"));
        assertThat(item.limitations()).noneMatch(s -> s.contains("resistance_strength") && s.contains("[0,1]"));
        assertThat(item.outputs().annualizedLossExpectancy()).isNotNull();
    }

    @Test
    void analyze_tcapAndRsWithoutVulnerability_doesNotInventVulnerability() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("contact_frequency", threePoint(10.0, 20.0, 40.0));
        inputs.put("probability_of_action", threePoint(0.1, 0.2, 0.5));
        inputs.put("threat_capability", threePoint(25.0, 50.0, 90.0));
        inputs.put("resistance_strength", threePoint(20.0, 40.0, 80.0));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.contains("P(TCap > RS)"));
        assertThat(item.outputs().lossEventFrequency()).isNull();
        assertThat(item.outputs().annualizedLossExpectancy()).isNull();
    }

    @Test
    void analyze_subFactors_surfacedInInputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("contact_frequency", threePoint(2.0, 3.0, 5.0));
        inputs.put("probability_of_action", threePoint(0.5, 0.7, 0.8));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("threat_capability", threePoint(30.0, 50.0, 70.0));
        inputs.put("resistance_strength", threePoint(40.0, 60.0, 80.0));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.inputs().contactFrequency()).isNotNull();
        assertThat(item.inputs().contactFrequency()).containsKey("low");
        assertThat(item.inputs().probabilityOfAction()).isNotNull();
        assertThat(item.inputs().probabilityOfAction()).containsKey("low");
        assertThat(item.inputs().threatCapability()).isNotNull();
        assertThat(item.inputs().resistanceStrength()).isNotNull();
        // With both TEF sub-factors present, no partial-lineage limitation should be emitted.
        assertThat(item.limitations())
                .noneMatch(s -> s.contains("contact_frequency") && s.contains("probability_of_action"));
    }

    @Test
    void analyze_filterByAssessmentId_returnsOneItem() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);

        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(assessment.getId(), projectId))
                .thenReturn(Optional.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, assessment.getId(), null);

        assertThat(result.assessments()).hasSize(1);
        assertThat(result.assessments().get(0).assessmentId()).isEqualTo(assessment.getId());
    }

    @Test
    void analyze_filterByAssessmentId_nonFairProfile_throwsDomainValidationException() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("likelihood_initiation", "HIGH");
        var nistAssessment = makeAssessment(nistProfile, inputs);
        UUID nistId = nistAssessment.getId();

        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(nistId, projectId))
                .thenReturn(Optional.of(nistAssessment));

        assertThatThrownBy(() -> service.analyze(projectId, null, nistId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("FAIR");
    }

    @Test
    void analyze_filterByAssessmentId_notFound_throwsNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(missing, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(projectId, null, missing, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void analyze_filterByRiskScenarioId_callsScenarioScopedQuery() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);

        when(riskAssessmentResultRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(
                        projectId, scenario.getId()))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, scenario.getId());

        assertThat(result.assessments()).hasSize(1);
    }

    @Test
    void analyze_countsGroupByRiskLevel_skipsNullRiskLevel() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment1 = makeAssessment(fairProfile, inputs);
        var assessment2 = makeAssessment(fairProfile, inputs);

        // assessment1 has persisted risk_level, assessment2 does not
        Map<String, Object> outputs1 = new LinkedHashMap<>();
        outputs1.put("risk_level", "HIGH");
        assessment1.setComputedOutputs(outputs1);

        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment1, assessment2));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        assertThat(result.counts().total()).isEqualTo(2);
        assertThat(result.counts().byRiskLevel()).containsKey("HIGH");
        assertThat(result.counts().byRiskLevel()).hasSize(1); // null risk level is skipped
    }

    @Test
    void analyze_asOfDefaultsToNow_whenNull() {
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        assertThat(result.asOf()).isEqualTo(FIXED_NOW);
    }

    @Test
    void analyze_asDoubleHelper_handlesIntegerDoubleString() {
        // TEF low as Integer (not Double) — should still work
        Map<String, Object> tef = new LinkedHashMap<>();
        tef.put("low", 1); // Integer
        tef.put("likely", 2.0); // Double
        tef.put("high", "4.0"); // String
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", tef);
        inputs.put("vulnerability", threePoint(0.5, 0.5, 0.5));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 1000.0, 1000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // low = 1 * 0.5 = 0.5
        assertThat(item.outputs().lossEventFrequency().low())
                .isEqualTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
        // high = 4.0 (from string) * 0.5 = 2.0
        assertThat(item.outputs().lossEventFrequency().high())
                .isEqualTo(2.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void analyze_percentileAbsent_emitsLimitation() {
        // When ALE is computed (not persisted) and no percentiles supplied
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.contains("ALE percentiles absent"));
    }

    @Test
    void analyze_riskLevel_passthroughFromPersistedOutputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);

        Map<String, Object> computedOutputs = new LinkedHashMap<>();
        computedOutputs.put("risk_level", "CRITICAL");
        assessment.setComputedOutputs(computedOutputs);

        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.outputs().riskLevel()).isEqualTo("CRITICAL");
    }

    // ---- GC-T016: FAIR materiality / loss-form decomposition + stakeholder secondary effects ----

    private Map<String, Object> stakeholderEntry(
            String stakeholder, String lossForm, double low, double likely, double high, String currency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stakeholder", stakeholder);
        if (lossForm != null) {
            m.put("loss_form", lossForm);
        }
        m.put("low", low);
        m.put("likely", likely);
        m.put("high", high);
        if (currency != null) {
            m.put("currency", currency);
        }
        return m;
    }

    @Test
    void analyze_formsOfLoss_decomposedByForm_withTotal() {
        Map<String, Object> formsOfLoss = new LinkedHashMap<>();
        formsOfLoss.put("productivity", threePoint(100.0, 200.0, 400.0));
        formsOfLoss.put("response", threePoint(50.0, 80.0, 120.0));
        formsOfLoss.put("replacement", threePoint(10.0, 20.0, 30.0));
        formsOfLoss.put("reputation", threePoint(500.0, 1500.0, 4000.0));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("forms_of_loss", formsOfLoss);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var materiality = result.assessments().get(0).outputs().materiality();
        assertThat(materiality).isNotNull();
        assertThat(materiality.currency()).isEqualTo("USD");
        assertThat(materiality.formsOfLoss())
                .extracting(b -> b.form())
                .containsExactlyInAnyOrder(
                        FairFormOfLoss.PRODUCTIVITY,
                        FairFormOfLoss.RESPONSE,
                        FairFormOfLoss.REPLACEMENT,
                        FairFormOfLoss.REPUTATION);
        // total = elementwise sum across the present forms
        assertThat(materiality.formsOfLossTotal().low()).isEqualTo(660.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(materiality.formsOfLossTotal().likely())
                .isEqualTo(1800.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(materiality.formsOfLossTotal().high())
                .isEqualTo(4550.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_formsOfLoss_partialForms_onlyPresentDecomposed() {
        Map<String, Object> formsOfLoss = new LinkedHashMap<>();
        formsOfLoss.put("productivity", threePoint(100.0, 200.0, 400.0));
        formsOfLoss.put("reputation", threePoint(500.0, 1500.0, 4000.0));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("forms_of_loss", formsOfLoss);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var materiality = result.assessments().get(0).outputs().materiality();
        assertThat(materiality).isNotNull();
        assertThat(materiality.formsOfLoss())
                .extracting(b -> b.form())
                .containsExactlyInAnyOrder(FairFormOfLoss.PRODUCTIVITY, FairFormOfLoss.REPUTATION);
        assertThat(materiality.formsOfLossTotal().low()).isEqualTo(600.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_formsOfLoss_currencyMismatchForm_excludedFromTotal_emitsLimitation() {
        Map<String, Object> formsOfLoss = new LinkedHashMap<>();
        formsOfLoss.put("productivity", threePointWithCurrency(100.0, 200.0, 400.0, "USD"));
        formsOfLoss.put("fines_and_judgments", threePointWithCurrency(1000.0, 2000.0, 3000.0, "EUR"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("forms_of_loss", formsOfLoss);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s -> s.toLowerCase().contains("fines_and_judgments")
                        && s.toLowerCase().contains("currenc"));
        var materiality = item.outputs().materiality();
        // The EUR form is excluded from the USD total; only productivity contributes.
        assertThat(materiality.formsOfLossTotal().low()).isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(materiality.formsOfLoss()).extracting(b -> b.form()).containsExactly(FairFormOfLoss.PRODUCTIVITY);
    }

    @Test
    void analyze_formsOfLoss_invalidRangeForm_excludedFromTotal_emitsLimitation() {
        Map<String, Object> formsOfLoss = new LinkedHashMap<>();
        // response out of order (low > high)
        formsOfLoss.put("response", threePoint(900.0, 80.0, 10.0));
        formsOfLoss.put("productivity", threePoint(100.0, 200.0, 400.0));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("forms_of_loss", formsOfLoss);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.toLowerCase().contains("response"));
        var materiality = item.outputs().materiality();
        assertThat(materiality.formsOfLoss()).extracting(b -> b.form()).containsExactly(FairFormOfLoss.PRODUCTIVITY);
        assertThat(materiality.formsOfLossTotal().low()).isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_secondaryLossByStakeholder_parsed_withAndWithoutLossForm() {
        List<Object> stakeholders = new java.util.ArrayList<>();
        stakeholders.add(stakeholderEntry("Customers", "reputation", 1000.0, 3000.0, 9000.0, "USD"));
        stakeholders.add(stakeholderEntry("Regulators", null, 500.0, 1500.0, 4000.0, "USD"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("secondary_loss_by_stakeholder", stakeholders);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var materiality = result.assessments().get(0).outputs().materiality();
        assertThat(materiality).isNotNull();
        assertThat(materiality.secondaryLossByStakeholder()).hasSize(2);
        var customers = materiality.secondaryLossByStakeholder().get(0);
        assertThat(customers.stakeholder()).isEqualTo("Customers");
        assertThat(customers.lossForm()).isEqualTo(FairFormOfLoss.REPUTATION);
        assertThat(customers.magnitude().likely()).isEqualTo(3000.0);
        var regulators = materiality.secondaryLossByStakeholder().get(1);
        assertThat(regulators.stakeholder()).isEqualTo("Regulators");
        assertThat(regulators.lossForm()).isNull();
    }

    @Test
    void analyze_stakeholderSecondaryLoss_invalidMagnitude_emitsLimitation_excluded() {
        List<Object> stakeholders = new java.util.ArrayList<>();
        // negative magnitude is invalid
        stakeholders.add(stakeholderEntry("Customers", "reputation", -100.0, 3000.0, 9000.0, "USD"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("secondary_loss_by_stakeholder", stakeholders);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations()).anyMatch(s -> s.toLowerCase().contains("customers"));
        assertThat(item.outputs().materiality().secondaryLossByStakeholder()).isEmpty();
    }

    @Test
    void analyze_stakeholderSecondaryLoss_currencyMismatch_excludedWithLimitation() {
        // A EUR stakeholder loss on a USD assessment must not be surfaced under the
        // single USD materiality currency — it is excluded with a limitation, mirroring
        // the FAIR-MAM cost-module currency handling.
        List<Object> stakeholders = new java.util.ArrayList<>();
        stakeholders.add(stakeholderEntry("Customers", "reputation", 1000.0, 3000.0, 9000.0, "EUR"));
        stakeholders.add(stakeholderEntry("Regulators", null, 500.0, 1500.0, 4000.0, "USD"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("secondary_loss_by_stakeholder", stakeholders);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(s ->
                        s.toLowerCase().contains("customers") && s.toLowerCase().contains("currenc"));
        var materiality = item.outputs().materiality();
        // Only the USD Regulators entry survives; the EUR Customers entry is excluded.
        assertThat(materiality.secondaryLossByStakeholder()).hasSize(1);
        assertThat(materiality.secondaryLossByStakeholder().get(0).stakeholder())
                .isEqualTo("Regulators");
    }

    @Test
    void analyze_noMaterialityData_materialityNull() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments().get(0).outputs().materiality()).isNull();
    }

    @Test
    void analyze_formsOfLoss_doesNotAffectAle() {
        // forms_of_loss is a descriptive materiality view — it must NOT change the canonical
        // ALE arithmetic (ALE = LEF * LM, LM = PLM). ALE must equal the value computed
        // without any forms_of_loss present.
        Map<String, Object> formsOfLoss = new LinkedHashMap<>();
        formsOfLoss.put("productivity", threePoint(999999.0, 999999.0, 999999.0));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("threat_event_frequency", threePoint(1.0, 2.0, 4.0));
        inputs.put("vulnerability", threePoint(0.1, 0.2, 0.4));
        inputs.put("primary_loss_magnitude", threePointWithCurrency(1000.0, 5000.0, 20000.0, "USD"));
        inputs.put("forms_of_loss", formsOfLoss);
        var assessment = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(assessment));

        FairQuantitativeAnalysisResult result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        // ALE low = LEF.low(0.1) * LM.low(1000) = 100 — unchanged by the huge forms_of_loss figure
        assertThat(item.outputs().annualizedLossExpectancy().low())
                .isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(item.outputs().annualizedLossExpectancy().high())
                .isEqualTo(32000.0, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void analyze_projectNotFound_throwsNotFoundException() {
        UUID unknownProjectId = UUID.randomUUID();
        when(projectRepository.findById(unknownProjectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(unknownProjectId, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknownProjectId.toString());
    }
}
