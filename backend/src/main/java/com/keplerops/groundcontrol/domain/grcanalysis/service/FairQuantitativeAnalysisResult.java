package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of a FAIR quantitative risk analysis per GC-T011.
 *
 * <p>Carries the GC-L007 preflight result-contract fields ({@code analysisKind},
 * {@code project}, {@code asOf}, {@code derivationMethod}, monetary
 * {@code scale}/{@code units}/{@code currency}, structured {@code inputs} /
 * {@code outputs} / {@code evidence}, percentile outputs, {@code limitations}) so
 * MCP/agent callers receive a methodology-attributed FAIR envelope — never a
 * collapsed generic risk score.
 *
 * <p>FAIR underlying factors (TEF, contact frequency, probability of action,
 * vulnerability, susceptibility, threat capability, resistance strength,
 * primary loss, secondary loss) flow through verbatim from
 * {@code RiskAssessmentResult.inputFactors}; the service derives ALE / LEF /
 * LM percentile envelopes via a seeded Monte Carlo so reproducibility is
 * auditable.
 *
 * <p>Monetary values carry an explicit {@code currency} (ISO-4217 string,
 * default {@code "USD"}) and {@code scale} ({@code "UNITS"} / {@code "THOUSANDS"}
 * / {@code "MILLIONS"}) so downstream consumers cannot silently mix currencies
 * or magnitudes.
 */
public record FairQuantitativeAnalysisResult(
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
            List<String> limitations) {}

    /**
     * Methodology-defined inputs in their original FAIR vocabulary. Factor
     * maps are passed through verbatim from the persisted
     * {@code RiskAssessmentResult.inputFactors} so analyst-supplied keys
     * (FAIR / FAIR-MAM stakeholders, loss forms) reach the caller untouched.
     */
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
            SimulationInputs simulation) {}

    public record SimulationInputs(int iterations, long seed) {}

    /**
     * Computed outputs. {@link #annualizedLossExpectancy},
     * {@link #lossEventFrequency}, and {@link #lossMagnitude} carry low /
     * likely / high three-point summaries and {@code percentiles}
     * (p5/p10/p50/p90/p95/p99) when Monte Carlo runs. Primary and secondary
     * loss rollups are kept separate per GC-T016 so executive reporting can
     * attribute the loss line independently.
     */
    public record FairOutputs(
            MonetaryEnvelope annualizedLossExpectancy,
            FrequencyEnvelope lossEventFrequency,
            MonetaryEnvelope lossMagnitude,
            MonetaryEnvelope primaryLossMagnitude,
            MonetaryEnvelope secondaryLossMagnitude,
            String derivation) {}

    public record MonetaryEnvelope(
            double low,
            double likely,
            double high,
            String currency,
            String scale,
            String units,
            Percentiles percentiles) {}

    public record FrequencyEnvelope(double low, double likely, double high, String units, Percentiles percentiles) {}

    public record Percentiles(double p5, double p10, double p50, double p90, double p95, double p99) {}

    public record Counts(int total, int withSimulation, int withLimitations) {}
}
