package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import com.keplerops.groundcontrol.domain.riskscenarios.state.AppetiteToleranceKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GC-T005 shared kernel — single source of appetite/tolerance arithmetic.
 *
 * <p>Three downstream consumers use this evaluator to avoid parallel
 * implementations of threshold arithmetic:
 * <ol>
 *   <li>{@code RiskAssessmentCampaignService} (GC-T006 EVALUATION phase) — labels
 *       each scenario inside a campaign against the bound appetite profile.
 *   <li>{@code KeyRiskIndicatorService} (GC-T007) — composes KRI breach signals
 *       with appetite labelling for downstream notification routing.
 *   <li>The cluster-3 {@code gc_analyze} risk-posture analysis kind (T008) —
 *       same evaluator, aggregated across the project.
 * </ol>
 *
 * <p>The evaluator is pure (no transactions, no IO) so callers control the read
 * boundary. A null profile or a profile with no tolerances classifies every
 * input as {@link Outcome#NOT_EVALUATED} — never silently {@code WITHIN} (the
 * difference matters when the next consumer is making a governance decision).
 */
@Component
public class RiskAppetiteEvaluator {

    /**
     * Evaluation outcome. The four bands match the contract documented on
     * {@link RiskAppetiteTolerance}: a value is within tolerance, approaching
     * the band ceiling, exceeds it, or could not be evaluated because no
     * matching tolerance band exists for the category.
     */
    public enum Outcome {
        WITHIN,
        APPROACHING,
        EXCEEDS,
        NOT_EVALUATED
    }

    /**
     * Result of a single appetite evaluation: outcome plus the tolerance band
     * matched (may be null when {@code outcome == NOT_EVALUATED}).
     */
    public record Result(Outcome outcome, RiskAppetiteTolerance matchedTolerance, String reason) {}

    /**
     * Evaluate a monetary loss-magnitude value (FAIR ALE / loss expectancy)
     * against the category's tolerance band.
     */
    public Result evaluateMonetary(RiskAppetiteProfile profile, String category, BigDecimal value, String currency) {
        var tolerance = matchTolerance(profile, category, AppetiteToleranceKind.MONETARY_RANGE);
        if (tolerance.isEmpty() || value == null) {
            return notEvaluated(category, "no MONETARY_RANGE tolerance for category");
        }
        var t = tolerance.get();
        if (currency != null && t.currency() != null && !currency.equalsIgnoreCase(t.currency())) {
            return notEvaluated(category, "currency mismatch: value=" + currency + " tolerance=" + t.currency());
        }
        if (t.monetaryHigh() != null && value.compareTo(t.monetaryHigh()) > 0) {
            return new Result(Outcome.EXCEEDS, t, "value exceeds monetaryHigh");
        }
        if (t.monetaryLow() != null && value.compareTo(t.monetaryLow()) >= 0) {
            return new Result(Outcome.APPROACHING, t, "value within monetary tolerance band");
        }
        return new Result(Outcome.WITHIN, t, "value below monetary tolerance lower bound");
    }

    /**
     * Evaluate an annualized loss event frequency (LEF, expected events per year)
     * against the category's LEF tolerance.
     */
    public Result evaluateLossEventFrequency(RiskAppetiteProfile profile, String category, BigDecimal value) {
        var tolerance = matchTolerance(profile, category, AppetiteToleranceKind.LOSS_EVENT_FREQUENCY);
        if (tolerance.isEmpty() || value == null) {
            return notEvaluated(category, "no LOSS_EVENT_FREQUENCY tolerance for category");
        }
        var t = tolerance.get();
        if (t.lossEventFrequencyMax() == null) {
            return notEvaluated(category, "tolerance has no lossEventFrequencyMax");
        }
        int cmp = value.compareTo(t.lossEventFrequencyMax());
        if (cmp > 0) {
            return new Result(Outcome.EXCEEDS, t, "LEF exceeds tolerance ceiling");
        }
        if (cmp == 0) {
            return new Result(Outcome.APPROACHING, t, "LEF equals tolerance ceiling");
        }
        return new Result(Outcome.WITHIN, t, "LEF within tolerance ceiling");
    }

    /**
     * Evaluate an exceedance probability (probability of exceeding the
     * tolerance's stated loss magnitude) against the category's EP cap.
     */
    public Result evaluateExceedanceProbability(RiskAppetiteProfile profile, String category, BigDecimal probability) {
        var tolerance = matchTolerance(profile, category, AppetiteToleranceKind.EXCEEDANCE_PROBABILITY);
        if (tolerance.isEmpty() || probability == null) {
            return notEvaluated(category, "no EXCEEDANCE_PROBABILITY tolerance for category");
        }
        var t = tolerance.get();
        if (t.exceedanceProbabilityMax() == null) {
            return notEvaluated(category, "tolerance has no exceedanceProbabilityMax");
        }
        int cmp = probability.compareTo(t.exceedanceProbabilityMax());
        if (cmp > 0) {
            return new Result(Outcome.EXCEEDS, t, "exceedance probability above tolerance");
        }
        if (cmp == 0) {
            return new Result(Outcome.APPROACHING, t, "exceedance probability at tolerance");
        }
        return new Result(Outcome.WITHIN, t, "exceedance probability below tolerance");
    }

    /**
     * Evaluate a qualitative band (e.g. NIST overall risk_level) against the
     * category's QUALITATIVE tolerance. The match is exact case-insensitive on
     * {@link RiskAppetiteTolerance#qualitativeLabel()}.
     */
    public Result evaluateQualitative(RiskAppetiteProfile profile, String category, String observedLabel) {
        var tolerance = matchTolerance(profile, category, AppetiteToleranceKind.QUALITATIVE);
        if (tolerance.isEmpty() || observedLabel == null) {
            return notEvaluated(category, "no QUALITATIVE tolerance for category");
        }
        var t = tolerance.get();
        if (t.qualitativeLabel() == null) {
            return notEvaluated(category, "tolerance has no qualitativeLabel");
        }
        if (t.qualitativeLabel().equalsIgnoreCase(observedLabel)) {
            return new Result(Outcome.APPROACHING, t, "observed label equals tolerance label");
        }
        return new Result(Outcome.WITHIN, t, "observed label differs from tolerance label");
    }

    private Optional<RiskAppetiteTolerance> matchTolerance(
            RiskAppetiteProfile profile, String category, AppetiteToleranceKind kind) {
        if (profile == null || profile.getTolerances() == null) {
            return Optional.empty();
        }
        List<RiskAppetiteTolerance> tolerances = profile.getTolerances();
        for (RiskAppetiteTolerance tolerance : tolerances) {
            if (Objects.equals(tolerance.kind(), kind)
                    && tolerance.category() != null
                    && tolerance.category().equalsIgnoreCase(category)) {
                return Optional.of(tolerance);
            }
        }
        return Optional.empty();
    }

    private Result notEvaluated(String category, String reason) {
        return new Result(Outcome.NOT_EVALUATED, null, "category=" + category + ": " + reason);
    }
}
