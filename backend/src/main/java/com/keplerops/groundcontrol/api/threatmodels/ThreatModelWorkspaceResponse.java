package com.keplerops.groundcontrol.api.threatmodels;

import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceResult;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
import java.util.List;
import java.util.UUID;

/**
 * HTTP DTO mirror of {@link ThreatModelWorkspaceResult} for the
 * {@code GET /api/v1/threat-models/workspace} endpoint (GC-Q010).
 */
public record ThreatModelWorkspaceResponse(
        List<WorkspaceAssetDto> assets,
        List<WorkspaceFlowDto> flows,
        List<WorkspaceThreatEntryDto> entries,
        int assetCount,
        int flowCount,
        int entryCount) {

    public static ThreatModelWorkspaceResponse from(ThreatModelWorkspaceResult result) {
        List<WorkspaceAssetDto> assets = result.assets().stream()
                .map(a -> new WorkspaceAssetDto(a.id(), a.uid(), a.name(), a.assetType(), a.isBoundary()))
                .toList();
        List<WorkspaceFlowDto> flows = result.flows().stream()
                .map(f -> new WorkspaceFlowDto(f.id(), f.sourceAssetId(), f.targetAssetId(), f.relationType()))
                .toList();
        List<WorkspaceThreatEntryDto> entries = result.entries().stream()
                .map(e -> new WorkspaceThreatEntryDto(
                        e.id(),
                        e.uid(),
                        e.title(),
                        e.status(),
                        e.stride(),
                        e.linkedAssetIds(),
                        e.linkedControls().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        e.linkedRequirements().stream()
                                .map(l -> new WorkspaceLinkDto(
                                        l.targetEntityId(), l.targetIdentifier(), l.targetTitle(), l.targetUrl()))
                                .toList(),
                        e.staleIndicator()))
                .toList();
        return new ThreatModelWorkspaceResponse(
                assets, flows, entries, result.assetCount(), result.flowCount(), result.entryCount());
    }

    public record WorkspaceAssetDto(UUID id, String uid, String name, AssetType assetType, boolean boundary) {}

    public record WorkspaceFlowDto(UUID id, UUID sourceAssetId, UUID targetAssetId, AssetRelationType relationType) {}

    public record WorkspaceThreatEntryDto(
            UUID id,
            String uid,
            String title,
            ThreatModelStatus status,
            StrideCategory stride,
            List<UUID> linkedAssetIds,
            List<WorkspaceLinkDto> linkedControls,
            List<WorkspaceLinkDto> linkedRequirements,
            String staleIndicator) {}

    public record WorkspaceLinkDto(
            UUID targetEntityId, String targetIdentifier, String targetTitle, String targetUrl) {}
}
