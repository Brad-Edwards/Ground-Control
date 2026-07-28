package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ADR-090 section 4 process variables (issue #1355).
 *
 * <p>Pure: it takes evaluable attempt rows and returns the formulas, with no repository, clock, or
 * Spring context. The definitions are the thing the measurement model exists to make comparable, so
 * they live somewhere they can be tested directly rather than inside a query.
 *
 * <p>Only {@code PASS} and {@code FAIL} attempts reach here. Skipped, cancelled, not-evaluable and
 * unobserved attempts remain measurable coverage but are excluded from these denominators — an
 * unmeasured gate must never read as a failing one.
 */
public final class StationYieldCalculator {

    private StationYieldCalculator() {}

    /**
     * One evaluable attempt at one station in one run.
     *
     * <p>{@code cycleIndex} is the attempt ordinal, not an arrival order: attempts are sequenced by
     * it so a late-delivered first attempt is never read as a retry of the one that arrived first.
     */
    public record AttemptRow(String stationId, UUID runId, Integer cycleIndex, StationResult result) {

        int ordinal() {
            // An emitter that cannot order attempts is describing the first one. That is what makes
            // an unordered reconciliation record converge instead of appending a phantom retry.
            return cycleIndex == null ? 0 : cycleIndex;
        }
    }

    /**
     * Per-station yield, rework, and iteration distribution.
     *
     * <p>Numerator, denominator, and unresolved count travel with the ratio because a percentage
     * without its coverage is not a process fact: a station inspected twice and a station inspected
     * two thousand times must not render identically.
     */
    public record StationYield(
            String stationId,
            long firstPassNumerator,
            long firstPassDenominator,
            long evaluableAttempts,
            long reworkAttempts,
            long unresolvedRuns,
            Map<Integer, Long> iterationsToGreen) {

        /** Null when nothing was measured — distinct from a measured zero. */
        public Double firstPassYield() {
            return firstPassDenominator == 0 ? null : (double) firstPassNumerator / firstPassDenominator;
        }
    }

    /**
     * Compute per-station yields from evaluable attempts.
     *
     * @param rows evaluable attempts in any order; grouping and sequencing happen here
     * @return station id to its yield, for stations that had at least one evaluable attempt
     */
    public static Map<String, StationYield> compute(List<AttemptRow> rows) {
        // station -> run -> ordered attempts
        Map<String, Map<UUID, List<AttemptRow>>> byStation = new LinkedHashMap<>();
        for (var row : rows) {
            if (row == null || row.stationId() == null || row.runId() == null || row.result() == null) {
                continue;
            }
            byStation
                    .computeIfAbsent(row.stationId(), unused -> new LinkedHashMap<>())
                    .computeIfAbsent(row.runId(), unused -> new ArrayList<>())
                    .add(row);
        }

        Map<String, StationYield> result = new LinkedHashMap<>();
        byStation.forEach((stationId, runs) -> {
            long firstPassPassed = 0;
            long runsWithAttempts = 0;
            long evaluableAttempts = 0;
            long reworkAttempts = 0;
            long unresolvedRuns = 0;
            Map<Integer, Long> iterations = new TreeMap<>();

            for (var attempts : runs.values()) {
                var ordered = sequence(attempts);
                if (ordered.isEmpty()) {
                    continue;
                }
                runsWithAttempts++;
                evaluableAttempts += ordered.size();
                if (ordered.get(0).result() == StationResult.PASS) {
                    firstPassPassed++;
                }
                int firstPass = indexOfFirstPass(ordered);
                if (firstPass < 0) {
                    // No pass occurred. Recorded as unresolved rather than substituted with a
                    // maximum, a zero, or a timeout, any of which would put a fabricated value into
                    // the distribution.
                    unresolvedRuns++;
                } else {
                    iterations.merge(firstPass + 1, 1L, Long::sum);
                    reworkAttempts += firstPass;
                }
            }

            result.put(
                    stationId,
                    new StationYield(
                            stationId,
                            firstPassPassed,
                            runsWithAttempts,
                            evaluableAttempts,
                            reworkAttempts,
                            unresolvedRuns,
                            iterations));
        });
        return result;
    }

    /**
     * Order one run's attempts and collapse duplicate identities.
     *
     * <p>A live observation and its backfilled copy describe the same attempt. Counting both would
     * invent rework that never happened, so the first row for an ordinal wins.
     */
    private static List<AttemptRow> sequence(List<AttemptRow> attempts) {
        var seen = new HashSet<Integer>();
        var ordered = new ArrayList<>(attempts);
        ordered.sort(Comparator.comparingInt(AttemptRow::ordinal));
        var deduped = new ArrayList<AttemptRow>(ordered.size());
        for (var attempt : ordered) {
            if (seen.add(attempt.ordinal())) {
                deduped.add(attempt);
            }
        }
        return deduped;
    }

    private static int indexOfFirstPass(List<AttemptRow> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).result() == StationResult.PASS) {
                return i;
            }
        }
        return -1;
    }
}
