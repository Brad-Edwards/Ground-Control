package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ADR-090 process variables over a window (issue #1355).
 *
 * <p>Every ratio ships with its numerator, denominator, and unresolved count, and the whole
 * response carries the measurement version it was computed against. A percentage without its
 * coverage is not a process fact: a station inspected twice and a station inspected two thousand
 * times must not render identically, and a consumer must be able to tell "measured zero" from
 * "nothing was measured".
 */
public record MeasurementAggregateResponse(
        Instant from,
        Instant to,
        String measurementVersion,
        List<StationYieldRow> stations,
        List<FindingCountRow> findingCounts) {

    /**
     * Per-station yield and rework.
     *
     * @param firstPassYield null when nothing evaluable was measured — never a defaulted zero
     * @param iterationsToGreen attempt ordinal of the first pass, to the number of runs at it
     * @param unresolvedRuns runs that never reached a pass; reported, never substituted with a
     *     maximum, so the resolved distribution stays honest
     */
    public record StationYieldRow(
            String stationId,
            long firstPassNumerator,
            long firstPassDenominator,
            Double firstPassYield,
            long evaluableAttempts,
            long reworkAttempts,
            long unresolvedRuns,
            Map<Integer, Long> iterationsToGreen) {}

    /**
     * Finding counts by the three axes issue #1355 names, plus disposition.
     *
     * <p>{@code category} and {@code severity} are nullable and that bucket is reported rather than
     * dropped: a source that expressed neither is a real observation, and silently excluding those
     * rows would make the counts disagree with the underlying findings.
     */
    public record FindingCountRow(
            String stationId,
            FindingSourceKind sourceKind,
            String sourceId,
            String category,
            String severity,
            FindingDisposition disposition,
            long count) {}
}
