package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FairQuantitativeAnalysisServiceTest {

    @Mock
    private RiskAssessmentResultRepository riskAssessmentResultRepository;

    @Mock
    private ProjectRepository projectRepository;

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

        fairProfile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        setField(fairProfile, "id", UUID.randomUUID());

        nistProfile = new MethodologyProfile(
                project, "NIST_SP800_30_R1", "NIST SP 800-30 Rev. 1", "1", MethodologyFamily.NIST_SP800_30_R1);
        setField(nistProfile, "id", UUID.randomUUID());
    }

    private RiskAssessmentResult makeAssessment(MethodologyProfile profile, Map<String, Object> inputs) {
        var result = new RiskAssessmentResult(project, scenario, profile);
        setField(result, "id", UUID.randomUUID());
        result.setInputFactors(inputs);
        result.setAssessmentAt(Instant.parse("2026-05-30T00:00:00Z"));
        return result;
    }

    private Map<String, Object> fairInputs(int seed, int iterations) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threat_event_frequency", Map.of("low", 1.0, "likely", 4.0, "high", 12.0));
        m.put("vulnerability", Map.of("low", 0.1, "likely", 0.4, "high", 0.7));
        m.put(
                "primary_loss_magnitude",
                Map.of("low", 1000.0, "likely", 5000.0, "high", 20000.0, "currency", "USD", "scale", "UNITS"));
        m.put("secondary_loss_event_frequency", Map.of("low", 0.1, "likely", 0.3, "high", 0.5));
        m.put("secondary_loss_magnitude", Map.of("low", 500.0, "likely", 2500.0, "high", 10000.0, "currency", "USD"));
        m.put("simulation", Map.of("seed", seed, "iterations", iterations));
        return m;
    }

    @Test
    void analyze_methodologyAttributedEnvelope_carriesScaleAndCurrency() {
        var row = makeAssessment(fairProfile, fairInputs(42, 1000));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, Instant.parse("2026-05-30T00:00:00Z"), null, null);

        assertThat(result.analysisKind()).isEqualTo("fair_analysis");
        assertThat(result.project()).isEqualTo("ground-control");
        assertThat(result.scale()).isEqualTo("continuous");
        assertThat(result.units()).isEqualTo("monetary per year");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.derivationMethod()).contains("monte-carlo");
        assertThat(result.assessments()).hasSize(1);
        var item = result.assessments().get(0);
        assertThat(item.profileKey()).isEqualTo("FAIR_V3_0");
        assertThat(item.family()).isEqualTo("FAIR");
        assertThat(item.outputs().annualizedLossExpectancy().currency()).isEqualTo("USD");
        assertThat(item.outputs().annualizedLossExpectancy().percentiles()).isNotNull();
        assertThat(item.outputs().lossEventFrequency().percentiles()).isNotNull();
        assertThat(item.outputs().lossMagnitude().percentiles()).isNotNull();
        // GC-T016: primary and secondary loss envelopes are surfaced
        // independently from the LM rollup. A regression that null'd either
        // field or pointed both at the same envelope would silently collapse
        // the primary/secondary distinction.
        assertThat(item.outputs().primaryLossMagnitude()).isNotNull();
        assertThat(item.outputs().secondaryLossMagnitude()).isNotNull();
        assertThat(item.outputs().primaryLossMagnitude().percentiles()).isNotNull();
        assertThat(item.outputs().secondaryLossMagnitude().percentiles()).isNotNull();
        // Fixture: primary likely ~ 5000, secondary likely ~ 2500 * 0.3 = 750.
        // The two envelopes MUST hold independent mean / percentile values, not
        // a shared reference.
        assertThat(item.outputs().primaryLossMagnitude().likely())
                .isNotEqualTo(item.outputs().secondaryLossMagnitude().likely());
        assertThat(item.outputs().primaryLossMagnitude().percentiles().p50())
                .isNotEqualTo(
                        item.outputs().secondaryLossMagnitude().percentiles().p50());
        // Primary contribution dominates LM by design (~5000 vs ~750), so the
        // primary envelope's mean is the larger of the two.
        assertThat(item.outputs().primaryLossMagnitude().likely())
                .isGreaterThan(item.outputs().secondaryLossMagnitude().likely());
    }

    @Test
    void analyze_secondaryPairAsymmetric_recordLimitationAndDropContribution() {
        // Finding 2: providing exactly one of {secondary_loss_event_frequency,
        // secondary_loss_magnitude} previously silently zeroed the secondary
        // contribution. We must fail loud.
        Map<String, Object> oneSided = new LinkedHashMap<>(fairInputs(42, 1000));
        oneSided.remove("secondary_loss_event_frequency");
        var row = makeAssessment(fairProfile, oneSided);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(l -> l.contains("secondary loss pair incomplete")
                        && l.contains("secondary_loss_magnitude")
                        && l.contains("secondary_loss_event_frequency"));
        // With the asymmetric pair dropped, the secondary envelope must be
        // zeroed — primary remains intact.
        assertThat(item.outputs().secondaryLossMagnitude().likely()).isEqualTo(0.0);
        assertThat(item.outputs().primaryLossMagnitude().likely()).isGreaterThan(0.0);
    }

    @Test
    void analyze_secondaryCurrencyDiffers_dropsContributionAndEmitsLimitation() {
        // Finding 1: secondary currency that differs from primary must NOT
        // silently roll up into the LM / ALE envelope. Drop the contribution
        // and emit a limitation.
        Map<String, Object> mixed = new LinkedHashMap<>(fairInputs(42, 1000));
        mixed.put(
                "secondary_loss_magnitude", Map.of("low", 500.0, "likely", 2500.0, "high", 10000.0, "currency", "EUR"));
        var row = makeAssessment(fairProfile, mixed);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(
                        l -> l.contains("secondary_loss_magnitude currency") && l.contains("EUR") && l.contains("USD"));
        assertThat(item.outputs().secondaryLossMagnitude().likely()).isEqualTo(0.0);
        // LM mean falls back to primary-only when secondary is dropped.
        assertThat(item.outputs().lossMagnitude().likely())
                .isEqualTo(item.outputs().primaryLossMagnitude().likely());
    }

    @Test
    void analyze_secondaryScaleDiffers_normalizesToPrimaryAndEmitsLimitation() {
        // Finding 1: secondary scale differing from primary should be
        // normalized (factor recorded as a limitation), not silently mixed.
        Map<String, Object> mixed = new LinkedHashMap<>(fairInputs(42, 2000));
        // Primary stays at UNITS (default in fairInputs).
        // Secondary in THOUSANDS — likely=2 thousand = 2000 in UNITS, which is
        // larger than the unnormalized 2.0 the math would otherwise see.
        mixed.put(
                "secondary_loss_magnitude",
                Map.of("low", 0.5, "likely", 2.0, "high", 10.0, "currency", "USD", "scale", "THOUSANDS"));
        var row = makeAssessment(fairProfile, mixed);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        var item = result.assessments().get(0);
        assertThat(item.limitations())
                .anyMatch(l -> l.contains("secondary_loss_magnitude scale")
                        && l.contains("THOUSANDS")
                        && l.contains("normalized"));
        // After normalization, secondary likely ~= 2.0 * 1000 * 0.3 (secondary
        // LEF likely) = ~600 in UNITS. Without normalization it would be 0.6.
        assertThat(item.outputs().secondaryLossMagnitude().likely()).isGreaterThan(100.0);
    }

    @Test
    void analyze_seededRng_reproducesIdenticalPercentiles() {
        // GC-T011: a deterministic seeded Monte Carlo means the same row
        // analyzed twice must return identical percentile envelopes.
        var row = makeAssessment(fairProfile, fairInputs(12345, 5000));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var first = service.analyze(projectId, null, null, null);
        var second = service.analyze(projectId, null, null, null);

        FairQuantitativeAnalysisResult.Percentiles p1 =
                first.assessments().get(0).outputs().annualizedLossExpectancy().percentiles();
        FairQuantitativeAnalysisResult.Percentiles p2 =
                second.assessments().get(0).outputs().annualizedLossExpectancy().percentiles();
        assertThat(p1).isEqualTo(p2);
    }

    @Test
    void analyze_percentileOrdering_monotonic() {
        var row = makeAssessment(fairProfile, fairInputs(7, 2000));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        var p = result.assessments().get(0).outputs().annualizedLossExpectancy().percentiles();
        assertThat(p.p5()).isLessThanOrEqualTo(p.p10());
        assertThat(p.p10()).isLessThanOrEqualTo(p.p50());
        assertThat(p.p50()).isLessThanOrEqualTo(p.p90());
        assertThat(p.p90()).isLessThanOrEqualTo(p.p95());
        assertThat(p.p95()).isLessThanOrEqualTo(p.p99());
    }

    @Test
    void analyze_filtersNonFairRows() {
        // FAIR analytics MUST skip rows on a non-FAIR methodology profile.
        var fairRow = makeAssessment(fairProfile, fairInputs(1, 500));
        var nistRow = makeAssessment(nistProfile, Map.of("impact_level", "HIGH"));
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(fairRow, nistRow));

        var result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments()).hasSize(1);
        assertThat(result.assessments().get(0).family()).isEqualTo("FAIR");
    }

    @Test
    void analyze_missingFactors_recordLimitation() {
        var row = makeAssessment(fairProfile, Map.of()); // no FAIR factors at all
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments()).hasSize(1);
        assertThat(result.assessments().get(0).limitations()).anyMatch(l -> l.contains("threat_event_frequency"));
        assertThat(result.assessments().get(0).outputs().derivation()).contains("not-derivable");
    }

    @Test
    void analyze_byAssessmentId_nonFairProfile_throws422() {
        var row = makeAssessment(nistProfile, Map.of("impact_level", "HIGH"));
        when(riskAssessmentResultRepository.findByIdAndProjectIdWithObservations(row.getId(), projectId))
                .thenReturn(Optional.of(row));

        DomainValidationException thrown = catchThrowableOfType(
                DomainValidationException.class, () -> service.analyze(projectId, null, row.getId(), null));
        assertThat(thrown).hasMessageContaining("not bound to a FAIR methodology profile");
    }

    @Test
    void analyze_projectNotFound_throws404() {
        UUID unknown = UUID.randomUUID();
        when(projectRepository.findById(unknown)).thenReturn(Optional.empty());

        NotFoundException thrown =
                catchThrowableOfType(NotFoundException.class, () -> service.analyze(unknown, null, null, null));
        assertThat(thrown).isNotNull();
    }

    @Test
    void analyze_iterationsClamp_emitsLimitation() {
        Map<String, Object> inputs = fairInputs(1, 1); // below min
        var row = makeAssessment(fairProfile, inputs);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments().get(0).limitations()).anyMatch(l -> l.contains("iterations"));
        // Min-iterations floor lives in FairQuantitativeAnalysisService as a
        // package-private constant (100). Below-floor requests are clamped up.
        assertThat(result.assessments().get(0).inputs().simulation().iterations())
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    void analyze_inverseOrder_clampedAndLimitationEmitted() {
        Map<String, Object> bad = new LinkedHashMap<>(fairInputs(1, 500));
        bad.put("threat_event_frequency", Map.of("low", 10.0, "likely", 5.0, "high", 2.0));
        var row = makeAssessment(fairProfile, bad);
        when(riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(row));

        var result = service.analyze(projectId, null, null, null);

        assertThat(result.assessments().get(0).limitations())
                .anyMatch(l -> l.contains("threat_event_frequency") && l.contains("low <= likely <= high"));
    }
}
