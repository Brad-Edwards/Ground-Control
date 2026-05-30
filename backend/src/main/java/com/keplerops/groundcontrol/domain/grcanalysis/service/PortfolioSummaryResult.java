package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable domain result for the GRC Portfolio Reporting Views per GC-Q013.
 *
 * <p>This is a <strong>read-only projection</strong> over existing aggregates — no new JPA aggregate,
 * table, or materialized view is introduced (GC-L007). It rolls the graph up into the executive- and
 * auditor-facing portfolio dimensions: risk posture, control health, evidence freshness, finding
 * trends, asset criticality concentration, and methodology-family summaries (FAIR / NIST / ISO). All
 * distribution maps are keyed by the originating enum's {@code name()} for a stable wire contract.
 *
 * <p>Each section also exposes <strong>actionable drill-down id lists</strong> behind its key signal
 * counts (critical assets, unmapped and unassessed controls, overdue register reviews, open and
 * overdue findings) so the UI can pivot from a summary number to the specific entities behind it.
 * The full distributions plus the dedicated control / risk / evidence workspaces cover the remaining
 * navigation; the methodology {@code family} is itself the drill-down key for its summary.
 */
public record PortfolioSummaryResult(
        String project,
        Instant asOf,
        String derivationMethod,
        RiskPosture riskPosture,
        ControlHealth controlHealth,
        EvidenceFreshness evidenceFreshness,
        FindingTrends findingTrends,
        AssetCriticality assetCriticality,
        List<MethodologySummary> methodologySummaries,
        List<String> limitations) {

    /**
     * Risk posture: scenario, assessment, treatment, and register distributions plus review signals.
     * {@code overdueRegisterRecordUids} are the register records whose {@code nextReviewAt} is past the
     * reference instant (the drill-down behind {@code overdueReviews}).
     */
    public record RiskPosture(
            int totalScenarios,
            Map<String, Integer> scenariosByStatus,
            int totalAssessments,
            Map<String, Integer> assessmentsByApprovalState,
            int totalTreatments,
            Map<String, Integer> treatmentsByStatus,
            Map<String, Integer> treatmentsByStrategy,
            int totalRegisterRecords,
            Map<String, Integer> registerByStatus,
            int reassessmentSignals,
            int overdueReviews,
            List<String> overdueRegisterRecordUids) {}

    /**
     * Control health: status and effectiveness distributions, with unassessed/unmapped gap counts and
     * the drill-down uid lists behind those two gap counts.
     */
    public record ControlHealth(
            int totalControls,
            Map<String, Integer> controlsByStatus,
            Map<String, Integer> designEffectivenessDistribution,
            Map<String, Integer> operatingEffectivenessDistribution,
            int unassessedControls,
            int unmappedControls,
            List<String> unassessedControlUids,
            List<String> unmappedControlUids) {}

    /** Evidence freshness roll-up, mirrored from the GC-L007 freshness analysis. */
    public record EvidenceFreshness(int fresh, int stale, int expired, int superseded, int currentlyValid) {}

    /**
     * Finding trends: severity / status / type distributions, with open and overdue counts plus the
     * drill-down uid lists behind those two counts.
     */
    public record FindingTrends(
            int totalFindings,
            Map<String, Integer> bySeverity,
            Map<String, Integer> byStatus,
            Map<String, Integer> byType,
            int openCount,
            int overdueCount,
            List<String> openFindingUids,
            List<String> overdueFindingUids) {}

    /** Asset criticality concentration: criticality / environment / scope distributions and critical ids. */
    public record AssetCriticality(
            int totalAssets,
            Map<String, Integer> byCriticality,
            Map<String, Integer> byEnvironment,
            Map<String, Integer> byScope,
            List<String> criticalAssetUids) {}

    /** A per-methodology-family summary (FAIR / NIST / ISO / CUSTOM): profile and assessment cardinality. */
    public record MethodologySummary(
            String family,
            int profileCount,
            int assessmentCount,
            int approvedAssessmentCount,
            int assessmentsWithComputedOutputs) {}
}
