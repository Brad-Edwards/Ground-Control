package com.keplerops.groundcontrol.domain.architecturemodel.service;

import java.util.List;
import java.util.UUID;

public record ArchitectureModelDiffResult(
        UUID fromSnapshotId, UUID toSnapshotId, List<ArchitectureModelDiffEntry> entries) {

    public ArchitectureModelDiffResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
