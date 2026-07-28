package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationYieldCalculator;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationYieldCalculator.AttemptRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-090 section 4 formulas (issue #1355).
 *
 * <p>These are the definitions the whole measurement model exists to make comparable, so each one is
 * pinned against the specific way it is usually got wrong: a late green retry silently improving
 * first-pass yield, an unresolved run being counted as some large number of iterations, and
 * non-evaluable attempts padding a denominator.
 */
class StationYieldCalculatorTest {

    private static final UUID RUN_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RUN_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID RUN_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static AttemptRow attempt(UUID run, String station, Integer cycle, StationResult result) {
        return new AttemptRow(station, run, cycle, result);
    }

    @Test
    void firstPassYieldCountsOnlyTheFirstEvaluableAttempt() {
        var yields = StationYieldCalculator.compute(List.of(
                attempt(RUN_A, "ci", 0, StationResult.PASS),
                attempt(RUN_B, "ci", 0, StationResult.FAIL),
                attempt(RUN_B, "ci", 1, StationResult.PASS)));

        var ci = yields.get("ci");
        // Run B eventually went green, but its FIRST inspection failed. A later green retry must
        // never improve first-pass yield — that is the entire point of the measure.
        assertThat(ci.firstPassNumerator()).isEqualTo(1);
        assertThat(ci.firstPassDenominator()).isEqualTo(2);
        assertThat(ci.firstPassYield()).isEqualTo(0.5d);
    }

    @Test
    void iterationsToGreenIsTheOrdinalOfTheFirstPass() {
        var yields = StationYieldCalculator.compute(List.of(
                attempt(RUN_A, "codex_review", 0, StationResult.FAIL),
                attempt(RUN_A, "codex_review", 1, StationResult.FAIL),
                attempt(RUN_A, "codex_review", 2, StationResult.PASS)));

        assertThat(yields.get("codex_review").iterationsToGreen()).containsExactlyEntriesOf(java.util.Map.of(3, 1L));
    }

    @Test
    void aRunThatNeverPassesIsUnresolvedRatherThanAssignedAMaximum() {
        var yields = StationYieldCalculator.compute(List.of(
                attempt(RUN_A, "sonarcloud", 0, StationResult.PASS),
                attempt(RUN_B, "sonarcloud", 0, StationResult.FAIL),
                attempt(RUN_B, "sonarcloud", 1, StationResult.FAIL)));

        var sonar = yields.get("sonarcloud");
        // Substituting a maximum, a zero, or a timeout would put a fabricated number into the
        // distribution and make the median meaningless.
        assertThat(sonar.unresolvedRuns()).isEqualTo(1);
        assertThat(sonar.iterationsToGreen()).containsExactlyEntriesOf(java.util.Map.of(1, 1L));
    }

    @Test
    void reworkIsIterationsToGreenMinusOne() {
        var yields = StationYieldCalculator.compute(
                List.of(attempt(RUN_A, "ci", 0, StationResult.FAIL), attempt(RUN_A, "ci", 1, StationResult.PASS)));

        assertThat(yields.get("ci").reworkAttempts()).isEqualTo(1);
    }

    @Test
    void attemptsAreOrderedByOrdinalNotByArrivalOrder() {
        // A late-delivered first attempt must not be read as a retry of the one that arrived first.
        var yields = StationYieldCalculator.compute(
                List.of(attempt(RUN_A, "ci", 1, StationResult.PASS), attempt(RUN_A, "ci", 0, StationResult.FAIL)));

        assertThat(yields.get("ci").firstPassNumerator()).isZero();
        assertThat(yields.get("ci").iterationsToGreen()).containsExactlyEntriesOf(java.util.Map.of(2, 1L));
    }

    @Test
    void oneAttemptIdentityIsCountedOnce() {
        // A live observation and its backfilled copy describe one attempt. Counting both would
        // invent rework that never happened.
        var yields = StationYieldCalculator.compute(List.of(
                attempt(RUN_A, "ci", 0, StationResult.FAIL),
                attempt(RUN_A, "ci", 0, StationResult.FAIL),
                attempt(RUN_A, "ci", 1, StationResult.PASS)));

        assertThat(yields.get("ci").evaluableAttempts()).isEqualTo(2);
        assertThat(yields.get("ci").reworkAttempts()).isEqualTo(1);
    }

    @Test
    void stationsAreKeptSeparate() {
        // Codex and test-quality have separate rework profiles; summing them into one synthetic
        // cycle count would make per-gate yield meaningless.
        var yields = StationYieldCalculator.compute(List.of(
                attempt(RUN_A, "codex_review", 0, StationResult.FAIL),
                attempt(RUN_A, "test_quality_review", 0, StationResult.PASS)));

        assertThat(yields.keySet()).containsExactlyInAnyOrder("codex_review", "test_quality_review");
        assertThat(yields.get("codex_review").firstPassNumerator()).isZero();
        assertThat(yields.get("test_quality_review").firstPassNumerator()).isEqualTo(1);
    }

    @Test
    void aStationWithNoEvaluableAttemptsReportsNoYieldRatherThanZero() {
        var yields = StationYieldCalculator.compute(List.of());

        // Zero percent and "nothing was measured" are different claims. An empty result set must
        // not render as a station that always fails.
        assertThat(yields).isEmpty();
    }

    @Test
    void nullOrdinalsAreTreatedAsTheFirstAttempt() {
        // An emitter that cannot order attempts is describing the first one; that is what makes an
        // unordered reconciliation record converge instead of appending a phantom retry.
        var yields = StationYieldCalculator.compute(List.of(attempt(RUN_C, "policy", null, StationResult.PASS)));

        assertThat(yields.get("policy").firstPassNumerator()).isEqualTo(1);
        assertThat(yields.get("policy").evaluableAttempts()).isEqualTo(1);
    }
}
