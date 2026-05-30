package com.keplerops.groundcontrol.domain.controls.service;

import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestMethodology;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain result for the Control and Assurance Workspace per GC-Q011.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. Each control is assembled with its scoped
 * implementations, control-test history and summary, latest effectiveness assessment, risk-control
 * mapping count (the observation/evidence provenance hinge), exceptions (findings linked to the
 * control), and an evidence-freshness staleness indicator over its linked assets. Owner work queues
 * roll the controls up per owner with an attention count.
 *
 * <p><strong>Attention interpretation (owner work queue signal).</strong> A control
 * {@code needsAttention} only when it is {@link ControlStatus#OPERATIONAL} and at least one of:
 * no effectiveness assessment exists; design or operating effectiveness is
 * {@link ControlEffectivenessRating#INEFFECTIVE}; the latest control test concluded
 * {@link ControlTestConclusion#INEFFECTIVE}; the evidence freshness over its linked assets is
 * STALE/EXPIRED; or it has an open (not {@link FindingStatus#VERIFIED_CLOSED}) exception. Non-operational
 * controls are never flagged — they are still being built out, so flagging them would be noise.
 *
 * <p>Assessment/test prose payloads (rationale, notes, test steps, expected/actual results) are never
 * projected — only structured ratings, conclusions, and provenance identities are surfaced.
 */
public record ControlWorkspaceResult(
        List<WorkspaceControl> controls, List<OwnerQueue> ownerQueues, List<WorkspaceAsset> assets) {

    public int controlCount() {
        return controls.size();
    }

    public int ownerQueueCount() {
        return ownerQueues.size();
    }

    public int assetCount() {
        return assets.size();
    }

    /** A control with its assurance composition and the owner-queue attention flag. */
    public record WorkspaceControl(
            UUID id,
            String uid,
            String title,
            ControlFunction controlFunction,
            ControlStatus status,
            String owner,
            String category,
            List<WorkspaceScopedImplementation> scopedImplementations,
            List<WorkspaceControlTest> tests,
            WorkspaceTestSummary testSummary,
            WorkspaceAssessment latestAssessment,
            int mappingCount,
            List<WorkspaceExceptionRef> exceptions,
            List<UUID> linkedAssetIds,
            String staleIndicator,
            boolean needsAttention) {}

    /** A scoped deployment of a catalog control. {@code operationalAssetId} is null when unanchored. */
    public record WorkspaceScopedImplementation(UUID id, String uid, String name, UUID operationalAssetId) {}

    /** A control-test record summary (prose fields excluded). */
    public record WorkspaceControlTest(
            UUID id,
            String uid,
            ControlTestMethodology methodology,
            ControlTestConclusion conclusion,
            LocalDate testDate,
            String testerIdentity) {}

    /** Roll-up of a control's test history. {@code latestConclusion}/{@code latestTestDate} are null when untested. */
    public record WorkspaceTestSummary(
            int total,
            int effective,
            int ineffective,
            int notTested,
            LocalDate latestTestDate,
            ControlTestConclusion latestConclusion) {}

    /** The most recent effectiveness assessment for a control (prose excluded). */
    public record WorkspaceAssessment(
            UUID id,
            String uid,
            ControlEffectivenessRating designEffectiveness,
            ControlEffectivenessRating operatingEffectiveness,
            LocalDate assessedAt,
            String assessor) {}

    /** A finding linked to the control as an exception/deficiency. */
    public record WorkspaceExceptionRef(
            UUID id,
            String uid,
            String title,
            FindingType findingType,
            FindingSeverity severity,
            FindingStatus status) {}

    /** A scoped operational asset. {@code isBoundary} is true when {@code assetType == BOUNDARY}. */
    public record WorkspaceAsset(UUID id, String uid, String name, AssetType assetType, boolean isBoundary) {}

    /** Owner work queue: all controls owned by {@code owner}, with the count and UIDs needing attention. */
    public record OwnerQueue(
            String owner, int totalControls, int attentionControls, List<String> attentionControlUids) {}
}
