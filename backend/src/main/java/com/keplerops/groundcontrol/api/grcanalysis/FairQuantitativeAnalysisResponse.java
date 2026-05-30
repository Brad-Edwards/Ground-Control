package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairQuantitativeAnalysisResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for the FAIR quantitative analysis endpoint per GC-T011. Decouples
 * the public JSON contract from the domain service record so future domain
 * refactors do not silently change the wire shape.
 */
public record FairQuantitativeAnalysisResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        String currency,
        List<FairAnalysisItem> assessments,
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
                result.assessments().stream().map(FairAnalysisItem::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record FairAnalysisItem(
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
            FairInputs inputs,
            FairOutputs outputs,
            List<String> evidenceRefs,
            List<String> limitations) {

        public static FairAnalysisItem from(FairQuantitativeAnalysisResult.FairAnalysisItem item) {
            return new FairAnalysisItem(
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
                    FairInputs.from(item.inputs()),
                    FairOutputs.from(item.outputs()),
                    List.copyOf(item.evidenceRefs()),
                    List.copyOf(item.limitations()));
        }
    }

    public record FairInputs(
            Map<String, Object> threatEventFrequency,
            Map<String, Object> contactFrequency,
            Map<String, Object> probabilityOfAction,
            Map<String, Object> vulnerability,
            Map<String, Object> susceptibility,
            Map<String, Object> threatCapability,
            Map<String, Object> resistanceStrength,
            Map<String, Object> lossEventFrequency,
            Map<String, Object> primaryLossMagnitude,
            Map<String, Object> secondaryLossEventFrequency,
            Map<String, Object> secondaryLossMagnitude,
            Map<String, Object> fairCam,
            SimulationInputs simulation) {

        public static FairInputs from(FairQuantitativeAnalysisResult.FairInputs i) {
            return new FairInputs(
                    i.threatEventFrequency(),
                    i.contactFrequency(),
                    i.probabilityOfAction(),
                    i.vulnerability(),
                    i.susceptibility(),
                    i.threatCapability(),
                    i.resistanceStrength(),
                    i.lossEventFrequency(),
                    i.primaryLossMagnitude(),
                    i.secondaryLossEventFrequency(),
                    i.secondaryLossMagnitude(),
                    i.fairCam(),
                    SimulationInputs.from(i.simulation()));
        }
    }

    public record SimulationInputs(int iterations, long seed) {
        public static SimulationInputs from(FairQuantitativeAnalysisResult.SimulationInputs s) {
            return new SimulationInputs(s.iterations(), s.seed());
        }
    }

    public record FairOutputs(
            MonetaryEnvelope annualizedLossExpectancy,
            FrequencyEnvelope lossEventFrequency,
            MonetaryEnvelope lossMagnitude,
            MonetaryEnvelope primaryLossMagnitude,
            MonetaryEnvelope secondaryLossMagnitude,
            String derivation) {

        public static FairOutputs from(FairQuantitativeAnalysisResult.FairOutputs o) {
            return new FairOutputs(
                    MonetaryEnvelope.from(o.annualizedLossExpectancy()),
                    FrequencyEnvelope.from(o.lossEventFrequency()),
                    MonetaryEnvelope.from(o.lossMagnitude()),
                    MonetaryEnvelope.from(o.primaryLossMagnitude()),
                    MonetaryEnvelope.from(o.secondaryLossMagnitude()),
                    o.derivation());
        }
    }

    public record MonetaryEnvelope(
            double low,
            double likely,
            double high,
            String currency,
            String scale,
            String units,
            Percentiles percentiles) {

        public static MonetaryEnvelope from(FairQuantitativeAnalysisResult.MonetaryEnvelope e) {
            return new MonetaryEnvelope(
                    e.low(),
                    e.likely(),
                    e.high(),
                    e.currency(),
                    e.scale(),
                    e.units(),
                    e.percentiles() == null ? null : Percentiles.from(e.percentiles()));
        }
    }

    public record FrequencyEnvelope(double low, double likely, double high, String units, Percentiles percentiles) {

        public static FrequencyEnvelope from(FairQuantitativeAnalysisResult.FrequencyEnvelope e) {
            return new FrequencyEnvelope(
                    e.low(),
                    e.likely(),
                    e.high(),
                    e.units(),
                    e.percentiles() == null ? null : Percentiles.from(e.percentiles()));
        }
    }

    public record Percentiles(double p5, double p10, double p50, double p90, double p95, double p99) {
        public static Percentiles from(FairQuantitativeAnalysisResult.Percentiles p) {
            return new Percentiles(p.p5(), p.p10(), p.p50(), p.p90(), p.p95(), p.p99());
        }
    }

    public record Counts(int total, int withSimulation, int withLimitations) {
        public static Counts from(FairQuantitativeAnalysisResult.Counts c) {
            return new Counts(c.total(), c.withSimulation(), c.withLimitations());
        }
    }
}
