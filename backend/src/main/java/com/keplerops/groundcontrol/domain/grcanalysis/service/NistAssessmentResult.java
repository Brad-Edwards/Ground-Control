package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatEventKind;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatSourceRelevance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of a NIST SP 800-30 Rev. 1 risk-assessment view per
 * GC-T014. Carries the GC-L007 preflight result-contract fields
 * ({@code analysisKind}, {@code project}, {@code asOf},
 * {@code derivationMethod}, {@code scale}, {@code units}, structured
 * {@code inputs} / {@code outputs}, {@code limitations}) so MCP/agent callers
 * receive a methodology-attributed envelope, not a generic risk score.
 *
 * <p>Likelihood and impact use ordinal Java enums; threat-source characteristics,
 * vulnerabilities, predisposing conditions, and the assessment timeframe pass
 * through as opaque {@link Map}s / {@link List}s so methodology-defined
 * key vocabularies stay verbatim (per ADR-035 / ADR-034 enum mirror policy and
 * the GC-T014 preflight opaque-key rule).
 */
public record NistAssessmentResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        String matrixConversionRule,
        List<NistAssessmentItem> assessments,
        Counts counts,
        List<String> limitations) {

    public record NistAssessmentItem(
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
            Map<String, Object> threatSource,
            Map<String, Object> threatEvent,
            ThreatEventKind threatEventKind,
            List<Map<String, Object>> vulnerabilities,
            List<Map<String, Object>> predisposingConditions,
            ThreatSourceRelevance threatEventRelevance,
            NistLikelihoodBand likelihoodInitiation,
            NistLikelihoodBand likelihoodAdverseImpact,
            NistLikelihoodBand likelihoodOverall,
            NistImpactBand impactLevel,
            Map<String, Object> assessmentTimeframe) {

        /**
         * Backward-compatible Java accessor for the original GC-T014 name. NIST
         * SP 800-30 Rev. 1 defines these bands as threat-event relevance in
         * Table E-4, not threat-source relevance.
         */
        public ThreatSourceRelevance threatSourceRelevance() {
            return threatEventRelevance;
        }
    }

    public record Outputs(
            NistLikelihoodBand overallLikelihood,
            NistImpactBand impactLevel,
            String riskLevel,
            String matrixCell,
            String derivation) {}

    public record Counts(int total, Map<String, Integer> byRiskLevel, int withLimitations) {}
}
