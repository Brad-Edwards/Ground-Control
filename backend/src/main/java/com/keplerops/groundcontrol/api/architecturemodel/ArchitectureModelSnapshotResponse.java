package com.keplerops.groundcontrol.api.architecturemodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelSnapshotView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArchitectureModelSnapshotResponse(
        UUID id,
        UUID derivationRunId,
        String projectIdentifier,
        String schemaVersion,
        String modelVersion,
        String commitSha,
        String source,
        String createdBy,
        int elementCount,
        int flowCount,
        List<ArchitectureModelElementResponse> elements,
        Instant createdAt,
        Instant updatedAt) {

    public static ArchitectureModelSnapshotResponse from(ArchitectureModelSnapshotView view) {
        var snapshot = view.snapshot();
        return new ArchitectureModelSnapshotResponse(
                snapshot.getId(),
                snapshot.getDerivationRun() == null
                        ? null
                        : snapshot.getDerivationRun().getId(),
                snapshot.getProject().getIdentifier(),
                snapshot.getSchemaVersion(),
                snapshot.getModelVersion(),
                snapshot.getCommitSha(),
                snapshot.getSource(),
                snapshot.getCreatedBy(),
                snapshot.getElementCount(),
                snapshot.getFlowCount(),
                view.states().stream()
                        .map(state -> ArchitectureModelElementResponse.from(state.getElement(), state))
                        .toList(),
                snapshot.getCreatedAt(),
                snapshot.getUpdatedAt());
    }
}
