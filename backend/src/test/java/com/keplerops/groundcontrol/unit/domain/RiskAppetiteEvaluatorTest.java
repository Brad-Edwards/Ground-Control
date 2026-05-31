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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskAppetiteEvaluatorTest {

    private final RiskAppetiteEvaluator evaluator = new RiskAppetiteEvaluator();

    private static RiskAppetiteProfile profileWith(List<RiskAppetiteTolerance> tolerances) {
        var profile = new RiskAppetiteProfile(new Project("p", "P"), "key", "name", "1");
        profile.setTolerances(tolerances);
        return profile;
    }

    // Three monetary outcome bands in a single parameterized test: below the lower
    // bound (WITHIN), inside the band (APPROACHING), and above the upper bound
    // (EXCEEDS). Tolerance band: monetaryLow=$100k, monetaryHigh=$500k, USD.
    @ParameterizedTest(name = "monetary value={0} → outcome={1}")
    @CsvSource({"50000,   WITHIN", "200000,  APPROACHING", "1000000, EXCEEDS"})
    void monetaryOutcomeForValueAgainstBand(String valueStr, Outcome expected) {
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
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal(valueStr), "USD");
        assertThat(result.outcome()).isEqualTo(expected);
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

    // Cycle-2 boundary coverage. evaluateLossEventFrequency has three branches:
    // value > max → EXCEEDS, value == max → APPROACHING, value < max → WITHIN.
    // A regression flipping > to >= on the EXCEEDS comparison would mis-band the
    // equality case unless these tests pin the boundary down.
    @Test
    void lefApproachingAtBoundaryReturnsApproaching() {
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
        var result = evaluator.evaluateLossEventFrequency(profile, "CYBER", new BigDecimal("0.5"));
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    @Test
    void lefWithinBelowMaxReturnsWithin() {
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
        var result = evaluator.evaluateLossEventFrequency(profile, "CYBER", new BigDecimal("0.49"));
        assertThat(result.outcome()).isEqualTo(Outcome.WITHIN);
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

    // Cycle-2 boundary coverage for evaluateExceedanceProbability. Branches mirror
    // LEF: value > max → EXCEEDS, value == max → APPROACHING, value < max → WITHIN.
    @Test
    void exceedanceProbabilityApproachingAtBoundaryReturnsApproaching() {
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
        var result = evaluator.evaluateExceedanceProbability(profile, "CYBER", new BigDecimal("0.10"));
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    @Test
    void exceedanceProbabilityExceedsAboveMaxReturnsExceeds() {
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
        var result = evaluator.evaluateExceedanceProbability(profile, "CYBER", new BigDecimal("0.20"));
        assertThat(result.outcome()).isEqualTo(Outcome.EXCEEDS);
    }

    @Test
    void qualitativeMatchingLabelReturnsApproaching() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER", AppetiteToleranceKind.QUALITATIVE, "HIGH", null, null, null, null, null, null, null)));
        var result = evaluator.evaluateQualitative(profile, "CYBER", "high");
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    // Cycle-2 coverage for the non-matching qualitative branch — a regression
    // returning EXCEEDS instead of WITHIN for an unrelated label would silently
    // mis-label observations against the appetite profile.
    @Test
    void qualitativeNonMatchingLabelReturnsWithin() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER", AppetiteToleranceKind.QUALITATIVE, "HIGH", null, null, null, null, null, null, null)));
        var result = evaluator.evaluateQualitative(profile, "CYBER", "LOW");
        assertThat(result.outcome()).isEqualTo(Outcome.WITHIN);
    }

    // Cycle-2 boundary coverage for the monetary band. Production contract:
    //   value > monetaryHigh  → EXCEEDS
    //   value >= monetaryLow  → APPROACHING (within the band)
    //   otherwise            → WITHIN (below the band lower bound)
    // The original suite used mid-band / well-above / well-below values; a
    // refactor flipping >= ↔ > on either bound would silently mis-band the
    // equality cases. These tests pin both boundary cases.
    @Test
    void monetaryApproachingAtLowBoundaryReturnsApproaching() {
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
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("100000"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    @Test
    void monetaryApproachingAtHighBoundaryReturnsApproaching() {
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
        var result = evaluator.evaluateMonetary(profile, "CYBER", new BigDecimal("500000"), "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.APPROACHING);
    }

    // ------------------------------------------------------------------
    // NOT_EVALUATED guard coverage. The production code explicitly guards
    // null value and a missing threshold field for each kind. Without these
    // tests, removing any guard would NPE at runtime but pass silently
    // under the original suite. Each kind has two cases: null input value,
    // and the relevant threshold field absent from the tolerance record.
    // ------------------------------------------------------------------

    @Test
    void monetaryNullValueReturnsNotEvaluated() {
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
        var result = evaluator.evaluateMonetary(profile, "CYBER", null, "USD");
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void lefNullValueReturnsNotEvaluated() {
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
        var result = evaluator.evaluateLossEventFrequency(profile, "CYBER", null);
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void lefToleranceMissingMaxReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.LOSS_EVENT_FREQUENCY,
                null,
                null,
                null,
                null,
                null, // lossEventFrequencyMax unset
                null,
                null,
                null)));
        var result = evaluator.evaluateLossEventFrequency(profile, "CYBER", new BigDecimal("1.0"));
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void exceedanceProbabilityNullValueReturnsNotEvaluated() {
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
        var result = evaluator.evaluateExceedanceProbability(profile, "CYBER", null);
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void exceedanceProbabilityToleranceMissingMaxReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.EXCEEDANCE_PROBABILITY,
                null,
                null,
                null,
                null,
                null,
                null, // exceedanceProbabilityMax unset
                null,
                null)));
        var result = evaluator.evaluateExceedanceProbability(profile, "CYBER", new BigDecimal("0.05"));
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void qualitativeNullValueReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER", AppetiteToleranceKind.QUALITATIVE, "HIGH", null, null, null, null, null, null, null)));
        var result = evaluator.evaluateQualitative(profile, "CYBER", null);
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void qualitativeToleranceMissingLabelReturnsNotEvaluated() {
        var profile = profileWith(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.QUALITATIVE,
                null, // qualitativeLabel unset
                null,
                null,
                null,
                null,
                null,
                null,
                null)));
        var result = evaluator.evaluateQualitative(profile, "CYBER", "HIGH");
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
    }
}
