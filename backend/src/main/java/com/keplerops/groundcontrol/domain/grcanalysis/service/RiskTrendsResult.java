package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Risk trend points derived from the Envers audit history of
 * {@code RiskRegisterRecord} status transitions (and, when a window covers
 * them, {@code RiskAssessmentResult} approval-state transitions) for GC-T008.
 *
 * <p>Each point reflects events occurring in a bucket interval (default
 * monthly). Per ADR-035 the envelope carries an explicit derivation method;
 * trend traversal of Envers audit history honors ADR-033 actor provenance —
 * actor names are not surfaced here, only event counts.
 */
public record RiskTrendsResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        List<TrendPoint> points,
        Counts counts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, Instant from, Instant to, String bucket, String entity) {}

    public record TrendPoint(
            Instant windowStart,
            Instant windowEnd,
            int totalRevisions,
            Map<String, Integer> byStatus,
            Map<String, Integer> byRevisionType) {}

    public record Counts(int totalEvents, int totalBuckets) {}
}
