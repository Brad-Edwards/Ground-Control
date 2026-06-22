package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskAppetiteEvaluationService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskAppetiteEvaluationServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Mock
    private RiskAppetiteProfileRepository appetiteProfileRepository;

    @Mock
    private RiskAssessmentResultRepository assessmentRepository;

    @Mock
    private ProjectRepository projectRepository;

    private RiskAppetiteEvaluationService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new RiskAppetiteEvaluationService(
                appetiteProfileRepository, assessmentRepository, projectRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    private RiskAppetiteProfile profile(MethodologyFamily family, List<ToleranceThreshold> thresholds) {
        var p = new RiskAppetiteProfile(
                project, "BOARD_APPETITE", "Board Risk Appetite", "1.0", family, Instant.parse("2026-01-01T00:00:00Z"));
        p.setStatus(RiskAppetiteProfileStatus.ACTIVE);
        p.setToleranceThresholds(thresholds);
        setField(p, "id", PROFILE_ID);
        return p;
    }

    private RiskAssessmentResult assessment(MethodologyFamily family, Map<String, Object> computedOutputs) {
        var methodology = new MethodologyProfile(project, "FAIR_V3_0", "Open FAIR", "1", family);
        setField(methodology, "id", UUID.randomUUID());
        var scenario = new RiskScenario(project, "RS-1", "Breach", "actor", "method", "asset", "effect");
        setField(scenario, "id", UUID.randomUUID());
        var row = new RiskAssessmentResult(project, scenario, methodology);
        setField(row, "id", UUID.randomUUID());
        row.setComputedOutputs(computedOutputs);
        return row;
    }

    private ToleranceThreshold quantitative(double max, String currency) {
        return new ToleranceThreshold(
                null, "annualized_loss_expectancy.likely", max, "USD", currency, null, null, "ALE ceiling");
    }

    private void stubDefaultLookup(RiskAppetiteProfile profile, List<RiskAssessmentResult> rows) {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(appetiteProfileRepository.findByIdAndProjectId(PROFILE_ID, PROJECT_ID))
                .thenReturn(Optional.of(profile));
        when(assessmentRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(rows);
    }

    @Test
    void quantitativeBreachIsFlaggedForEscalation() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var row = assessment(MethodologyFamily.FAIR, Map.of("annualized_loss_expectancy", Map.of("likely", 600000.0)));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        assertThat(result.evaluations()).hasSize(1);
        var e = result.evaluations().get(0);
        assertThat(e.withinAppetite()).isFalse();
        assertThat(e.breached()).isTrue();
        assertThat(e.escalate()).isTrue();
        assertThat(result.summary().escalations()).isEqualTo(1);
        assertThat(result.analysisKind()).isEqualTo("appetite_evaluation");
    }

    @Test
    void quantitativeWithinAppetiteIsNotBreached() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var row = assessment(MethodologyFamily.FAIR, Map.of("annualized_loss_expectancy", Map.of("likely", 400000.0)));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        assertThat(result.evaluations().get(0).withinAppetite()).isTrue();
        assertThat(result.summary().breached()).isZero();
    }

    @Test
    void ordinalBreachIsDetectedViaScaleOrdering() {
        var threshold = new ToleranceThreshold(
                null, "risk_level", null, null, null, "MODERATE", List.of("LOW", "MODERATE", "HIGH", "CRITICAL"), null);
        var p = profile(MethodologyFamily.NIST_SP800_30_R1, List.of(threshold));
        var row = assessment(MethodologyFamily.NIST_SP800_30_R1, Map.of("risk_level", "HIGH"));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        assertThat(result.evaluations().get(0).breached()).isTrue();
    }

    @Test
    void currencyMismatchProducesLimitationNotComparison() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var row = assessment(MethodologyFamily.FAIR, Map.of("annualized_loss_expectancy", Map.of("likely", 600000.0)));
        row.setInputFactors(Map.of("primary_loss_magnitude", Map.of("currency", "EUR")));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        var e = result.evaluations().get(0);
        assertThat(e.withinAppetite()).isNull();
        assertThat(e.limitations()).isNotEmpty();
        assertThat(result.summary().notDerivable()).isEqualTo(1);
    }

    @Test
    void familyMismatchSkipsRowAndRecordsLimitation() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var row = assessment(MethodologyFamily.NIST_SP800_30_R1, Map.of("risk_level", "HIGH"));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.limitations()).anyMatch(l -> l.contains("methodology family"));
    }

    @Test
    void missingMetricIsNotDerivable() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var row = assessment(MethodologyFamily.FAIR, Map.of("risk_level", "HIGH"));
        stubDefaultLookup(p, List.of(row));

        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null);

        assertThat(result.evaluations().get(0).withinAppetite()).isNull();
        assertThat(result.summary().notDerivable()).isEqualTo(1);
    }

    @Test
    void resolvesActiveProfileByAppetiteKeyAsOf() {
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(appetiteProfileRepository.findByProjectIdAndAppetiteKeyAndStatus(
                        PROJECT_ID, "BOARD_APPETITE", RiskAppetiteProfileStatus.ACTIVE))
                .thenReturn(List.of(p));
        when(assessmentRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of());

        var result = service.evaluate(PROJECT_ID, null, null, "BOARD_APPETITE", null, null);

        assertThat(result.profile().appetiteKey()).isEqualTo("BOARD_APPETITE");
    }

    @Test
    void bothScopeFiltersComposeAsIntersection() {
        var registerRecordId = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
        var wantedScenarioId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        var otherScenarioId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        var p = profile(MethodologyFamily.FAIR, List.of(quantitative(500000.0, "USD")));
        var wanted =
                assessment(MethodologyFamily.FAIR, Map.of("annualized_loss_expectancy", Map.of("likely", 600000.0)));
        setField(wanted.getRiskScenario(), "id", wantedScenarioId);
        var other =
                assessment(MethodologyFamily.FAIR, Map.of("annualized_loss_expectancy", Map.of("likely", 900000.0)));
        setField(other.getRiskScenario(), "id", otherScenarioId);

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(appetiteProfileRepository.findByIdAndProjectId(PROFILE_ID, PROJECT_ID))
                .thenReturn(Optional.of(p));
        when(assessmentRepository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                        PROJECT_ID, registerRecordId))
                .thenReturn(List.of(wanted, other));

        // Both filters supplied: only the assessment matching BOTH the register record and the
        // scenario is evaluated — the other scenario's row must not inflate the escalation count.
        var result = service.evaluate(PROJECT_ID, null, PROFILE_ID, null, registerRecordId, wantedScenarioId);

        assertThat(result.evaluations()).hasSize(1);
        assertThat(result.evaluations().get(0).riskScenarioId()).isEqualTo(wantedScenarioId);
    }

    @Test
    void throwsWhenProfileNotFound() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(appetiteProfileRepository.findByIdAndProjectId(PROFILE_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(PROJECT_ID, null, PROFILE_ID, null, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void throwsWhenNeitherProfileIdNorKeyProvided() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.evaluate(PROJECT_ID, null, null, null, null, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
