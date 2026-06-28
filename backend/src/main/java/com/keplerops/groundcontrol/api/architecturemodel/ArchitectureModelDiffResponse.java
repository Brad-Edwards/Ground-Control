package com.keplerops.groundcontrol.api.architecturemodel;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffResult;
import java.util.List;
import java.util.UUID;

public record ArchitectureModelDiffResponse(
        UUID fromSnapshotId, UUID toSnapshotId, List<ArchitectureModelDiffEntryResponse> entries) {

    public static ArchitectureModelDiffResponse from(ArchitectureModelDiffResult result) {
        return new ArchitectureModelDiffResponse(
                result.fromSnapshotId(),
                result.toSnapshotId(),
                result.entries().stream()
                        .map(ArchitectureModelDiffEntryResponse::from)
                        .toList());
    }
}
