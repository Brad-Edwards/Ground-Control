package com.keplerops.groundcontrol.domain.architecturemodel.service;

import java.util.List;
import java.util.UUID;

public record CreateArchitectureModelSnapshotCommand(
        UUID projectId,
        String modelVersion,
        String commitSha,
        String source,
        String createdBy,
        List<ArchitectureModelElementStateCommand> elements) {

    public CreateArchitectureModelSnapshotCommand {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public CreateArchitectureModelSnapshotCommand(
            UUID projectId,
            String modelVersion,
            String commitSha,
            String source,
            List<ArchitectureModelElementStateCommand> elements) {
        this(projectId, modelVersion, commitSha, source, null, elements);
    }
}
