package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API DTO for the GC-T008 risk trends projection.
 */
public record RiskTrendsResponse(
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

    public static RiskTrendsResponse from(RiskTrendsResult result) {
        return new RiskTrendsResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                Inputs.from(result.inputs()),
                result.points().stream().map(TrendPoint::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, Instant from, Instant to, String bucket, String entity) {

        public static Inputs from(RiskTrendsResult.Inputs inputs) {
            return new Inputs(
                    inputs.project(), inputs.asOf(), inputs.from(), inputs.to(), inputs.bucket(), inputs.entity());
        }
    }

    public record TrendPoint(
            Instant windowStart,
            Instant windowEnd,
            int totalRevisions,
            Map<String, Integer> byStatus,
            Map<String, Integer> byRevisionType) {

        public static TrendPoint from(RiskTrendsResult.TrendPoint point) {
            return new TrendPoint(
                    point.windowStart(),
                    point.windowEnd(),
                    point.totalRevisions(),
                    Map.copyOf(point.byStatus()),
                    Map.copyOf(point.byRevisionType()));
        }
    }

    public record Counts(int totalEvents, int totalBuckets) {

        public static Counts from(RiskTrendsResult.Counts counts) {
            return new Counts(counts.totalEvents(), counts.totalBuckets());
        }
    }
}
