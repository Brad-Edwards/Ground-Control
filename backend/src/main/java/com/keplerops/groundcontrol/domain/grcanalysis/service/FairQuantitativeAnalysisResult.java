package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of an Open FAIR quantitative risk analysis per GC-T011.
 * Carries the GC-L007 preflight result-contract fields
 * ({@code analysisKind}, {@code project}, {@code asOf},
 * {@code derivationMethod}, {@code scale}, {@code units}, structured
 * {@code inputs} / {@code outputs}, {@code limitations}) so MCP/agent callers
 * receive a methodology-attributed envelope, not a generic risk score.
 *
 * <p>FAIR factor maps (threat_event_frequency, vulnerability, primary_loss_magnitude,
 * etc.) pass through as opaque {@link Map}s so methodology-defined
 * key vocabularies stay verbatim (per ADR-035 / ADR-034 enum mirror policy and
 * the GC-T011 preflight opaque-key rule).
 */
public record FairQuantitativeAnalysisResult(
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
            List<String> limitations) {}

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
            Map<String, Object> uncertaintyMetadata) {}

    public record Outputs(
            ThreePoint lossEventFrequency,
            ThreePoint lossMagnitude,
            ThreePoint annualizedLossExpectancy,
            String currency,
            Map<String, Object> percentiles,
            String riskLevel,
            String derivation,
            Materiality materiality) {}

    /**
     * FAIR materiality view per GC-T016. A descriptive decomposition of the
     * {@code forms_of_loss} loss-magnitude breakdown into the six O-RT forms of
     * loss, plus stakeholder-specific secondary effects classified by the same
     * forms of loss. {@code null} when neither {@code forms_of_loss} nor
     * stakeholder data is present. This view never feeds the canonical ALE
     * arithmetic (ALE stays {@code LEF × LM}), so it adds granular materiality
     * reasoning without changing cross-assessment comparability or risking
     * double-counting.
     */
    public record Materiality(
            List<FormOfLossBreakdown> formsOfLoss,
            ThreePoint formsOfLossTotal,
            String currency,
            List<StakeholderSecondaryLoss> secondaryLossByStakeholder) {}

    /** A single O-RT FAIR form of loss with its three-point monetary magnitude. */
    public record FormOfLossBreakdown(FairFormOfLoss form, ThreePoint magnitude) {}

    /**
     * A stakeholder-specific secondary loss effect. {@code lossForm} (an O-RT FAIR
     * form of loss) is {@code null} when the entry does not name one.
     */
    public record StakeholderSecondaryLoss(String stakeholder, FairFormOfLoss lossForm, ThreePoint magnitude) {}

    public record ThreePoint(Double low, Double likely, Double high) {}

    public record Counts(int total, Map<String, Integer> byRiskLevel, int withLimitations) {}
}
