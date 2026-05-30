package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executive risk posture summary for GC-T008. Aggregates risk-register status
 * distribution, the count of risks pending reassessment (per the
 * {@code reassessmentRequiredAt} signal set by the C8 listener), open vs.
 * accepted/closed counts, and the assessment approval-state distribution.
 *
 * <p>The cluster-3 architectural note routes detailed appetite/tolerance
 * evaluation through the shared {@code RiskAppetiteEvaluator} kernel from
 * cluster 1. That kernel is not yet available when this service ships, so the
 * envelope explicitly labels the posture as kernel-derived once the kernel
 * lands and meanwhile emits a {@code limitations} entry making the deferral
 * explicit — never collapsing FAIR dollars + NIST bands + compliance posture
 * into a single generic score (the ADR-035 gotcha called out in the
 * preflight).
 */
public record RiskPostureResult(
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

    public record Inputs(String project, Instant asOf) {}

    public record StatusSummary(
            int totalRecords, int openCount, int acceptedCount, int closedCount, Map<String, Integer> byStatus) {}

    public record ApprovalSummary(int totalAssessments, Map<String, Integer> byApprovalState) {}

    public record ReassessmentSummary(int pendingReassessmentCount, int totalAssessmentsConsidered) {}
}
