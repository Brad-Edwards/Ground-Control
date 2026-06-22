package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Methodology-attributed result of evaluating risk assessment results against a risk appetite
 * profile (GC-T005). Read-only derivation: residual values from {@code RiskAssessmentResult}
 * computed outputs are compared against the profile's tolerance ceilings. Comparisons that cannot
 * be performed (missing metric, unit/currency/scale mismatch, non-numeric value) surface as
 * per-item and top-level {@code limitations} rather than silent passes.
 */
public record RiskAppetiteEvaluationResult(
        String projectIdentifier,
        String analysisKind,
        Instant asOf,
        String derivationMethod,
        ProfileSummary profile,
        List<Evaluation> evaluations,
        Summary summary,
        List<String> limitations) {

    public record ProfileSummary(
            UUID id,
            String appetiteKey,
            String version,
            MethodologyFamily methodologyFamily,
            RiskAppetiteProfileStatus status,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    public record Evaluation(
            UUID riskAssessmentResultId,
            UUID riskScenarioId,
            String riskScenarioUid,
            UUID riskRegisterRecordId,
            String riskCategory,
            String metricPath,
            String residualValue,
            String thresholdValue,
            String units,
            Boolean withinAppetite,
            boolean breached,
            boolean escalate,
            List<String> limitations) {}

    public record Summary(int evaluated, int breached, int escalations, int notDerivable) {}
}
