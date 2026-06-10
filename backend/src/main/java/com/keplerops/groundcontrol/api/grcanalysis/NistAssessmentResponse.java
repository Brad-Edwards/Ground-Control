package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.NistAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatEventKind;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatSourceRelevance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for NIST SP 800-30 Rev. 1 assessment analysis. Decouples the public
 * JSON contract from the domain service record so future domain refactors do
 * not silently change the wire shape (preflight + existing api/grcanalysis
 * pattern).
 */
public record NistAssessmentResponse(
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

    public static NistAssessmentResponse from(NistAssessmentResult result) {
        return new NistAssessmentResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                result.matrixConversionRule(),
                result.assessments().stream().map(NistAssessmentItem::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

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
            List<String> limitations) {

        public static NistAssessmentItem from(NistAssessmentResult.NistAssessmentItem item) {
            return new NistAssessmentItem(
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
            Map<String, Object> threatSource,
            Map<String, Object> threatEvent,
            ThreatEventKind threatEventKind,
            List<Map<String, Object>> vulnerabilities,
            List<Map<String, Object>> predisposingConditions,
            ThreatSourceRelevance threatSourceRelevance,
            NistLikelihoodBand likelihoodInitiation,
            NistLikelihoodBand likelihoodAdverseImpact,
            NistLikelihoodBand likelihoodOverall,
            NistImpactBand impactLevel,
            Map<String, Object> assessmentTimeframe) {

        public static Inputs from(NistAssessmentResult.Inputs inputs) {
            return new Inputs(
                    inputs.threatSource(),
                    inputs.threatEvent(),
                    inputs.threatEventKind(),
                    inputs.vulnerabilities(),
                    inputs.predisposingConditions(),
                    inputs.threatSourceRelevance(),
                    inputs.likelihoodInitiation(),
                    inputs.likelihoodAdverseImpact(),
                    inputs.likelihoodOverall(),
                    inputs.impactLevel(),
                    inputs.assessmentTimeframe());
        }
    }

    public record Outputs(
            NistLikelihoodBand overallLikelihood,
            NistImpactBand impactLevel,
            String riskLevel,
            String matrixCell,
            String derivation) {

        public static Outputs from(NistAssessmentResult.Outputs outputs) {
            return new Outputs(
                    outputs.overallLikelihood(),
                    outputs.impactLevel(),
                    outputs.riskLevel(),
                    outputs.matrixCell(),
                    outputs.derivation());
        }
    }

    public record Counts(int total, Map<String, Integer> byRiskLevel, int withLimitations) {

        public static Counts from(NistAssessmentResult.Counts counts) {
            return new Counts(counts.total(), Map.copyOf(counts.byRiskLevel()), counts.withLimitations());
        }
    }
}
