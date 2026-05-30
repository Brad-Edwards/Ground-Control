package com.keplerops.groundcontrol.api.controls;

import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult;
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
 * HTTP DTO mirror of {@link ControlWorkspaceResult} for the
 * {@code GET /api/v1/controls/workspace} endpoint (GC-Q011).
 */
public record ControlWorkspaceResponse(
        List<WorkspaceControlDto> controls,
        List<OwnerQueueDto> ownerQueues,
        List<WorkspaceAssetDto> assets,
        int controlCount,
        int ownerQueueCount,
        int assetCount) {

    public static ControlWorkspaceResponse from(ControlWorkspaceResult result) {
        List<WorkspaceControlDto> controls = result.controls().stream()
                .map(ControlWorkspaceResponse::toControlDto)
                .toList();
        List<OwnerQueueDto> ownerQueues = result.ownerQueues().stream()
                .map(q -> new OwnerQueueDto(
                        q.owner(), q.totalControls(), q.attentionControls(), q.attentionControlUids()))
                .toList();
        List<WorkspaceAssetDto> assets = result.assets().stream()
                .map(a -> new WorkspaceAssetDto(a.id(), a.uid(), a.name(), a.assetType(), a.isBoundary()))
                .toList();
        return new ControlWorkspaceResponse(
                controls, ownerQueues, assets, result.controlCount(), result.ownerQueueCount(), result.assetCount());
    }

    private static WorkspaceControlDto toControlDto(ControlWorkspaceResult.WorkspaceControl c) {
        return new WorkspaceControlDto(
                c.id(),
                c.uid(),
                c.title(),
                c.controlFunction(),
                c.status(),
                c.owner(),
                c.category(),
                c.scopedImplementations().stream()
                        .map(s ->
                                new WorkspaceScopedImplementationDto(s.id(), s.uid(), s.name(), s.operationalAssetId()))
                        .toList(),
                c.tests().stream()
                        .map(t -> new WorkspaceControlTestDto(
                                t.id(), t.uid(), t.methodology(), t.conclusion(), t.testDate(), t.testerIdentity()))
                        .toList(),
                new WorkspaceTestSummaryDto(
                        c.testSummary().total(),
                        c.testSummary().effective(),
                        c.testSummary().ineffective(),
                        c.testSummary().notTested(),
                        c.testSummary().latestTestDate(),
                        c.testSummary().latestConclusion()),
                c.latestAssessment() == null
                        ? null
                        : new WorkspaceAssessmentDto(
                                c.latestAssessment().id(),
                                c.latestAssessment().uid(),
                                c.latestAssessment().designEffectiveness(),
                                c.latestAssessment().operatingEffectiveness(),
                                c.latestAssessment().assessedAt(),
                                c.latestAssessment().assessor()),
                c.mappingCount(),
                c.exceptions().stream()
                        .map(e -> new WorkspaceExceptionDto(
                                e.id(), e.uid(), e.title(), e.findingType(), e.severity(), e.status()))
                        .toList(),
                c.linkedAssetIds(),
                c.staleIndicator(),
                c.needsAttention());
    }

    public record WorkspaceControlDto(
            UUID id,
            String uid,
            String title,
            ControlFunction controlFunction,
            ControlStatus status,
            String owner,
            String category,
            List<WorkspaceScopedImplementationDto> scopedImplementations,
            List<WorkspaceControlTestDto> tests,
            WorkspaceTestSummaryDto testSummary,
            WorkspaceAssessmentDto latestAssessment,
            int mappingCount,
            List<WorkspaceExceptionDto> exceptions,
            List<UUID> linkedAssetIds,
            String staleIndicator,
            boolean needsAttention) {}

    public record WorkspaceScopedImplementationDto(UUID id, String uid, String name, UUID operationalAssetId) {}

    public record WorkspaceControlTestDto(
            UUID id,
            String uid,
            ControlTestMethodology methodology,
            ControlTestConclusion conclusion,
            LocalDate testDate,
            String testerIdentity) {}

    public record WorkspaceTestSummaryDto(
            int total,
            int effective,
            int ineffective,
            int notTested,
            LocalDate latestTestDate,
            ControlTestConclusion latestConclusion) {}

    public record WorkspaceAssessmentDto(
            UUID id,
            String uid,
            ControlEffectivenessRating designEffectiveness,
            ControlEffectivenessRating operatingEffectiveness,
            LocalDate assessedAt,
            String assessor) {}

    public record WorkspaceExceptionDto(
            UUID id,
            String uid,
            String title,
            FindingType findingType,
            FindingSeverity severity,
            FindingStatus status) {}

    public record WorkspaceAssetDto(UUID id, String uid, String name, AssetType assetType, boolean boundary) {}

    public record OwnerQueueDto(
            String owner, int totalControls, int attentionControls, List<String> attentionControlUids) {}
}
