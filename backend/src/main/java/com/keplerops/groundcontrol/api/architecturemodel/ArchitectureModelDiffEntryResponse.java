package com.keplerops.groundcontrol.api.architecturemodel;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffEntry;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffStatus;

public record ArchitectureModelDiffEntryResponse(String stableKey, ArchitectureModelDiffStatus status, String summary) {

    public static ArchitectureModelDiffEntryResponse from(ArchitectureModelDiffEntry entry) {
        return new ArchitectureModelDiffEntryResponse(entry.stableKey(), entry.status(), entry.summary());
    }
}
