package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTopNResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API DTO for the GC-T008 top-N risk projection.
 */
public record RiskTopNResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        List<TopNEntry> entries,
        Counts counts,
        List<String> limitations) {

    public static RiskTopNResponse from(RiskTopNResult result) {
        return new RiskTopNResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                Inputs.from(result.inputs()),
                result.entries().stream().map(TopNEntry::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, int limit, String orderBy) {

        public static Inputs from(RiskTopNResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.limit(), inputs.orderBy());
        }
    }

    public record TopNEntry(
            int rank,
            UUID riskAssessmentResultId,
            UUID riskScenarioId,
            String riskScenarioUid,
            String riskScenarioTitle,
            UUID methodologyProfileId,
            String methodologyFamily,
            String rankingMetric,
            String rankingValue,
            String approvalState,
            Instant assessmentAt,
            List<String> limitations) {

        public static TopNEntry from(RiskTopNResult.TopNEntry entry) {
            return new TopNEntry(
                    entry.rank(),
                    entry.riskAssessmentResultId(),
                    entry.riskScenarioId(),
                    entry.riskScenarioUid(),
                    entry.riskScenarioTitle(),
                    entry.methodologyProfileId(),
                    entry.methodologyFamily(),
                    entry.rankingMetric(),
                    entry.rankingValue(),
                    entry.approvalState(),
                    entry.assessmentAt(),
                    List.copyOf(entry.limitations()));
        }
    }

    public record Counts(int totalConsidered, int totalReturned) {

        public static Counts from(RiskTopNResult.Counts counts) {
            return new Counts(counts.totalConsidered(), counts.totalReturned());
        }
    }
}
