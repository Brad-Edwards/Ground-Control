package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskAppetiteEvaluationResult;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public JSON contract for the appetite-evaluation analysis (GC-T005), decoupled from the domain record. */
public record RiskAppetiteEvaluationResponse(
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

    public static RiskAppetiteEvaluationResponse from(RiskAppetiteEvaluationResult result) {
        RiskAppetiteEvaluationResult.ProfileSummary p = result.profile();
        ProfileSummary profile = new ProfileSummary(
                p.id(),
                p.appetiteKey(),
                p.version(),
                p.methodologyFamily(),
                p.status(),
                p.effectiveFrom(),
                p.effectiveTo());
        List<Evaluation> evaluations = result.evaluations().stream()
                .map(e -> new Evaluation(
                        e.riskAssessmentResultId(),
                        e.riskScenarioId(),
                        e.riskScenarioUid(),
                        e.riskRegisterRecordId(),
                        e.riskCategory(),
                        e.metricPath(),
                        e.residualValue(),
                        e.thresholdValue(),
                        e.units(),
                        e.withinAppetite(),
                        e.breached(),
                        e.escalate(),
                        e.limitations()))
                .toList();
        Summary summary = new Summary(
                result.summary().evaluated(),
                result.summary().breached(),
                result.summary().escalations(),
                result.summary().notDerivable());
        return new RiskAppetiteEvaluationResponse(
                result.projectIdentifier(),
                result.analysisKind(),
                result.asOf(),
                result.derivationMethod(),
                profile,
                evaluations,
                summary,
                result.limitations());
    }
}
