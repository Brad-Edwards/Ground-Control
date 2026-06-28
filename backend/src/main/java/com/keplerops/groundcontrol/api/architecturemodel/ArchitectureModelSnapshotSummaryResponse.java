package com.keplerops.groundcontrol.api.architecturemodel;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * List-view projection of an architecture-model snapshot. Carries the snapshot metadata and element/flow
 * counts but deliberately omits the per-element payload: a snapshot can hold up to 10,000 elements and the
 * snapshot history is unbounded, so embedding every element of every snapshot in the list response would
 * produce very large REST/MCP/gc_query payloads. Fetch a single snapshot via {@code getSnapshot} for the
 * full element state.
 */
public record ArchitectureModelSnapshotSummaryResponse(
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
        Instant createdAt,
        Instant updatedAt) {

    public static ArchitectureModelSnapshotSummaryResponse from(ArchitectureModelSnapshot snapshot) {
        return new ArchitectureModelSnapshotSummaryResponse(
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
                snapshot.getCreatedAt(),
                snapshot.getUpdatedAt());
    }
}
