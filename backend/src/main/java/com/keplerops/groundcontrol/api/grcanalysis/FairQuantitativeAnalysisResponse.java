package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for Open FAIR quantitative risk analysis per GC-T011. Decouples the
 * public JSON contract from the domain service record so future domain refactors
 * do not silently change the wire shape (preflight + existing api/grcanalysis
 * pattern).
 */
public record FairQuantitativeAnalysisResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        String currency,
        List<FairAssessmentItem> assessments,
        Counts counts,
        List<String> limitations) {

    public static FairQuantitativeAnalysisResponse from(FairQuantitativeAnalysisResult result) {
        return new FairQuantitativeAnalysisResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                result.currency(),
                result.assessments().stream().map(FairAssessmentItem::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record FairAssessmentItem(
            UUID assessmentId,
            UUID riskScenarioId,
            UUID methodologyProfileId,
            String profileKey,
            String family,
            String version,
            Instant assessmentAt,
            String timeHorizon,
            String analystIdentity,
            String approvalState,
            Inputs inputs,
            Outputs outputs,
            List<String> evidenceRefs,
            List<String> limitations) {

        public static FairAssessmentItem from(FairQuantitativeAnalysisResult.FairAssessmentItem item) {
            return new FairAssessmentItem(
                    item.assessmentId(),
                    item.riskScenarioId(),
                    item.methodologyProfileId(),
                    item.profileKey(),
                    item.family(),
                    item.version(),
                    item.assessmentAt(),
                    item.timeHorizon(),
                    item.analystIdentity(),
                    item.approvalState(),
                    Inputs.from(item.inputs()),
                    Outputs.from(item.outputs()),
                    List.copyOf(item.evidenceRefs()),
                    List.copyOf(item.limitations()));
        }
    }

    public record Inputs(
            Map<String, Object> threatEventFrequency,
            Map<String, Object> contactFrequency,
            Map<String, Object> probabilityOfAction,
            Map<String, Object> vulnerability,
            Map<String, Object> threatCapability,
            Map<String, Object> resistanceStrength,
            Map<String, Object> lossEventFrequency,
            Map<String, Object> primaryLossMagnitude,
            Map<String, Object> secondaryLossEventFrequency,
            Map<String, Object> secondaryLossMagnitude,
            Map<String, Object> uncertaintyMetadata) {

        public static Inputs from(FairQuantitativeAnalysisResult.Inputs inputs) {
            return new Inputs(
                    inputs.threatEventFrequency(),
                    inputs.contactFrequency(),
                    inputs.probabilityOfAction(),
                    inputs.vulnerability(),
                    inputs.threatCapability(),
                    inputs.resistanceStrength(),
                    inputs.lossEventFrequency(),
                    inputs.primaryLossMagnitude(),
                    inputs.secondaryLossEventFrequency(),
                    inputs.secondaryLossMagnitude(),
                    inputs.uncertaintyMetadata());
        }
    }

    public record Outputs(
            ThreePoint lossEventFrequency,
            ThreePoint lossMagnitude,
            ThreePoint annualizedLossExpectancy,
            String currency,
            Map<String, Object> percentiles,
            String riskLevel,
            String derivation) {

        public static Outputs from(FairQuantitativeAnalysisResult.Outputs outputs) {
            return new Outputs(
                    outputs.lossEventFrequency() == null ? null : ThreePoint.from(outputs.lossEventFrequency()),
                    outputs.lossMagnitude() == null ? null : ThreePoint.from(outputs.lossMagnitude()),
                    outputs.annualizedLossExpectancy() == null
                            ? null
                            : ThreePoint.from(outputs.annualizedLossExpectancy()),
                    outputs.currency(),
                    outputs.percentiles(),
                    outputs.riskLevel(),
                    outputs.derivation());
        }
    }

    public record ThreePoint(Double low, Double likely, Double high) {

        public static ThreePoint from(FairQuantitativeAnalysisResult.ThreePoint tp) {
            return new ThreePoint(tp.low(), tp.likely(), tp.high());
        }
    }

    public record Counts(int total, Map<String, Integer> byRiskLevel, int withLimitations) {

        public static Counts from(FairQuantitativeAnalysisResult.Counts counts) {
            return new Counts(counts.total(), Map.copyOf(counts.byRiskLevel()), counts.withLimitations());
        }
    }
}
