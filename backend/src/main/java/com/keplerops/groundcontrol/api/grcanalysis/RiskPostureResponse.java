package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskPostureResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API DTO for the GC-T008 executive risk posture summary.
 */
public record RiskPostureResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        StatusSummary statusSummary,
        ApprovalSummary approvalSummary,
        ReassessmentSummary reassessmentSummary,
        List<String> limitations) {

    public static RiskPostureResponse from(RiskPostureResult result) {
        return new RiskPostureResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                Inputs.from(result.inputs()),
                StatusSummary.from(result.statusSummary()),
                ApprovalSummary.from(result.approvalSummary()),
                ReassessmentSummary.from(result.reassessmentSummary()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf) {

        public static Inputs from(RiskPostureResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf());
        }
    }

    public record StatusSummary(
            int totalRecords, int openCount, int acceptedCount, int closedCount, Map<String, Integer> byStatus) {

        public static StatusSummary from(RiskPostureResult.StatusSummary summary) {
            return new StatusSummary(
                    summary.totalRecords(),
                    summary.openCount(),
                    summary.acceptedCount(),
                    summary.closedCount(),
                    Map.copyOf(summary.byStatus()));
        }
    }

    public record ApprovalSummary(int totalAssessments, Map<String, Integer> byApprovalState) {

        public static ApprovalSummary from(RiskPostureResult.ApprovalSummary summary) {
            return new ApprovalSummary(summary.totalAssessments(), Map.copyOf(summary.byApprovalState()));
        }
    }

    public record ReassessmentSummary(int pendingReassessmentCount, int totalAssessmentsConsidered) {

        public static ReassessmentSummary from(RiskPostureResult.ReassessmentSummary summary) {
            return new ReassessmentSummary(summary.pendingReassessmentCount(), summary.totalAssessmentsConsidered());
        }
    }
}
