package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain result for the Risk Scenario workspace per GC-Q009.
 *
 * <p>This is a read-only composition over existing aggregates — no JPA entities, no web types.
 * The workspace assembles:
 * <ul>
 *   <li>Scoped risk scenarios with their link buckets (ASSET/CONTROL/FINDING/EVIDENCE/REQUIREMENT)</li>
 *   <li>Assessments grouped per scenario (approval state, reassessment signal, confidence)</li>
 *   <li>Treatment plans grouped per scenario</li>
 *   <li>Risk register record memberships per scenario</li>
 *   <li>Scoped operational assets (boundaries distinguished by assetType)</li>
 * </ul>
 *
 * <p>Assessment payloads ({@code inputFactors}, {@code computedOutputs}, {@code uncertaintyMetadata},
 * {@code notes} prose) are never echoed — only a boolean {@code hasComputedOutputs} is surfaced
 * (preflight error-leakage rule).
 *
 * <p>Review indicator uses only explicit signals: {@code reassessmentRequiredAt} (from
 * {@link WorkspaceAssessment}), register {@code nextReviewAt}, and evidence freshness
 * dominant state — never {@code updatedAt}, Envers history, or lifecycle fields.
 */
public record RiskScenarioWorkspaceResult(List<WorkspaceScenario> scenarios, List<WorkspaceAsset> assets) {

    public int scenarioCount() {
        return scenarios.size();
    }

    public int assetCount() {
        return assets.size();
    }

    /**
     * A scoped risk scenario with its grouped links, assessments, treatments, register memberships,
     * and review indicator.
     *
     * <p>{@code reviewIndicator} is the worst explicit signal among:
     * REASSESSMENT_REQUIRED > REVIEW_DUE > EVIDENCE_STALE > CURRENT > NO_SIGNAL.
     */
    public record WorkspaceScenario(
            UUID id,
            String uid,
            String title,
            RiskScenarioStatus status,
            String threat,
            String method,
            String asset,
            String effect,
            String timeHorizon,
            String fairSentence,
            List<UUID> linkedAssetIds,
            List<WorkspaceLink> linkedControls,
            List<WorkspaceLink> linkedFindings,
            List<WorkspaceLink> linkedEvidence,
            List<WorkspaceLink> linkedRequirements,
            List<WorkspaceAssessment> assessments,
            List<WorkspaceTreatment> treatments,
            List<WorkspaceRegisterRef> registerRecords,
            String reviewIndicator) {}

    /**
     * A scoped operational asset. {@code isBoundary} is true when
     * {@code assetType == BOUNDARY} — callers may use this flag for visual
     * distinction without re-checking the enum.
     */
    public record WorkspaceAsset(UUID id, String uid, String name, AssetType assetType, boolean isBoundary) {}

    /**
     * A risk assessment result summary. Payloads (inputFactors, computedOutputs,
     * uncertaintyMetadata, notes) are intentionally excluded — only {@code hasComputedOutputs}
     * is surfaced (preflight error-leakage rule, GC-Q009).
     */
    public record WorkspaceAssessment(
            UUID id,
            String methodologyProfileName,
            RiskAssessmentApprovalStatus approvalState,
            Instant assessmentAt,
            String confidence,
            Instant reassessmentRequiredAt,
            boolean hasComputedOutputs) {}

    /**
     * A treatment plan summary linked to this scenario.
     */
    public record WorkspaceTreatment(
            UUID id,
            String uid,
            String title,
            TreatmentStrategy strategy,
            TreatmentPlanStatus status,
            String owner,
            Instant dueDate) {}

    /**
     * A link target (control, finding, evidence, or requirement). {@code targetEntityId} is the
     * internal UUID when the link points to a first-class entity; may be null for
     * external/not-yet-modeled artifacts.
     */
    public record WorkspaceLink(UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}

    /**
     * A risk register record membership reference for this scenario.
     * {@code nextReviewAt} is the explicit review-cadence signal used to compute the review indicator;
     * it is null when no review cadence has been set.
     */
    public record WorkspaceRegisterRef(
            UUID id, String uid, String title, RiskRegisterStatus status, java.time.Instant nextReviewAt) {}
}
