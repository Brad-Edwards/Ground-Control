package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAppetiteEvaluator;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAppetiteEvaluator.Outcome;
import com.keplerops.groundcontrol.domain.riskscenarios.state.AppetiteToleranceKind;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskAppetiteEvaluatorTest {

    private final RiskAppetiteEvaluator evaluator = new RiskAppetiteEvaluator();

    private static RiskAppetiteProfile profileWith(List<RiskAppetiteTolerance> tolerances) {
        var profile = new RiskAppetiteProfile(new Project("p", "P"), "key", "name", "1");
        profile.setTolerances(tolerances);
        return profile;
    }

    @Test
    void monetaryWithinReturnsWithin() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("50000"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.WITHIN);
    }

    @Test
    void monetaryApproachingReturnsApproaching() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("200000"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    @Test
    void monetaryExceedingReturnsExceeds() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("1000000"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.EXCEEDS);
    }

    @Test
    void monetaryCurrencyMismatchReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("100"), "EUR");
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void unknownCategoryReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                "USD",
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateMonetary(profile, "OPERATIONAL", new BigDecimal("50"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void nullProfileReturnsNotEvaluated() {
        var result = evaluator.evaluateMonetary(null, "CYBER", BigDecimal.TEN, "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void lefExceedsReturnsExceeds() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.LOSS_EVENT_FREQUENCY,
                null,
                null,
                null,
                null,
                new BigDecimal("0.5"),
                null,
                null,
                null)));
        var result = evaluator.evaluateLossEventFrequency(profile, "CYBER", new BigDecimal("1.0"));
        assertThat(result.outcome()).isEqualTo(Outcome.EXCEEDS);
    }

    @Test
    void exceedanceProbabilityWithinReturnsWithin() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.EXCEEDANCE_PROBABILITY,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("0.10"),
                null,
                null)));
        var result = evaluator.evaluateExceedanceProbability(profile, "CYBER", new BigDecimal("0.05"));
        assertThat(result.outcome()).isEqualTo(Outcome.WITHIN);
    }

    @Test
    void qualitativeMatchingLabelReturnsApproaching() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER", AppetiteToleranceKind.QUALITATIVE, "HIGH", null, null, null, null, null, null, null)));
        var result = evaluator.evaluateQualitative(profile, "CYBER", "high");
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }
}
