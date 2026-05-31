package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API DTO for the GC-T008 risk distribution projection.
 */
public record RiskDistributionResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        List<DistributionBucket> buckets,
        Counts counts,
        List<String> limitations) {

    public static RiskDistributionResponse from(RiskDistributionResult result) {
        return new RiskDistributionResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                Inputs.from(result.inputs()),
                result.buckets().stream().map(DistributionBucket::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, String groupBy) {

        public static Inputs from(RiskDistributionResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.groupBy());
        }
    }

    public record DistributionBucket(String key, String label, int count) {

        public static DistributionBucket from(RiskDistributionResult.DistributionBucket bucket) {
            return new DistributionBucket(bucket.key(), bucket.label(), bucket.count());
        }
    }

    public record Counts(int totalRecords, int recordsCounted, int recordsUnclassified, Map<String, Integer> totals) {

        public static Counts from(RiskDistributionResult.Counts counts) {
            return new Counts(
                    counts.totalRecords(),
                    counts.recordsCounted(),
                    counts.recordsUnclassified(),
                    Map.copyOf(counts.totals()));
        }
    }
}
