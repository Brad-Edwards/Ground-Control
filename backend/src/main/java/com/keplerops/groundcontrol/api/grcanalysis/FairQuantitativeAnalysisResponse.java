package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairFormOfLoss;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for FAIR v3.0 quantitative risk analysis per GC-T011. Decouples the
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
            Map<String, Object> fairCam,
            Map<String, Object> fairMam,
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
                    inputs.fairCam(),
                    inputs.fairMam(),
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
            String derivation,
            Materiality materiality) {

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
                    outputs.derivation(),
                    outputs.materiality() == null ? null : Materiality.from(outputs.materiality()));
        }
    }

    public record Materiality(
            List<FormOfLossBreakdown> formsOfLoss,
            ThreePoint formsOfLossTotal,
            String currency,
            List<StakeholderSecondaryLoss> secondaryLossByStakeholder) {

        public static Materiality from(FairQuantitativeAnalysisResult.Materiality materiality) {
            return new Materiality(
                    materiality.formsOfLoss().stream()
                            .map(FormOfLossBreakdown::from)
                            .toList(),
                    materiality.formsOfLossTotal() == null ? null : ThreePoint.from(materiality.formsOfLossTotal()),
                    materiality.currency(),
                    materiality.secondaryLossByStakeholder().stream()
                            .map(StakeholderSecondaryLoss::from)
                            .toList());
        }
    }

    public record FormOfLossBreakdown(FairFormOfLoss form, ThreePoint magnitude) {

        public static FormOfLossBreakdown from(FairQuantitativeAnalysisResult.FormOfLossBreakdown breakdown) {
            return new FormOfLossBreakdown(
                    breakdown.form(), breakdown.magnitude() == null ? null : ThreePoint.from(breakdown.magnitude()));
        }
    }

    public record StakeholderSecondaryLoss(String stakeholder, FairFormOfLoss lossForm, ThreePoint magnitude) {

        public static StakeholderSecondaryLoss from(FairQuantitativeAnalysisResult.StakeholderSecondaryLoss loss) {
            return new StakeholderSecondaryLoss(
                    loss.stakeholder(),
                    loss.lossForm(),
                    loss.magnitude() == null ? null : ThreePoint.from(loss.magnitude()));
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
