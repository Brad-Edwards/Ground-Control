package com.keplerops.groundcontrol.api.derivation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelAssignment;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelBoundary;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelGap;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryModelBuildResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoundaryModelSnapshotResponse(
        UUID id,
        UUID derivationRunId,
        String projectIdentifier,
        String schemaVersion,
        String boundarySetVersion,
        String architectureModelVersion,
        String commitSha,
        String declarationDigest,
        int boundaryCount,
        int assignmentCount,
        int gapCount,
        List<BoundaryResponse> boundaries,
        List<AssignmentResponse> assignments,
        List<GapResponse> gaps,
        Instant createdAt,
        Instant updatedAt) {

    public static BoundaryModelSnapshotResponse from(BoundaryModelBuildResult result) {
        if (result == null) {
            return null;
        }
        var snapshot = result.snapshot();
        return new BoundaryModelSnapshotResponse(
                snapshot.getId(),
                snapshot.getDerivationRun().getId(),
                snapshot.getProject().getIdentifier(),
                snapshot.getSchemaVersion(),
                snapshot.getBoundarySetVersion(),
                snapshot.getArchitectureModelVersion(),
                snapshot.getCommitSha(),
                snapshot.getDeclarationDigest(),
                snapshot.getBoundaryCount(),
                snapshot.getAssignmentCount(),
                snapshot.getGapCount(),
                result.boundaries().stream().map(BoundaryResponse::from).toList(),
                result.assignments().stream().map(AssignmentResponse::from).toList(),
                result.gaps().stream().map(GapResponse::from).toList(),
                snapshot.getCreatedAt(),
                snapshot.getUpdatedAt());
    }

    public record BoundaryResponse(
            UUID id,
            String boundaryKey,
            String displayName,
            String description,
            String source,
            List<String> pathSelectors,
            List<String> surfaces,
            List<String> inputFactKeys) {

        static BoundaryResponse from(BoundaryModelBoundary boundary) {
            return new BoundaryResponse(
                    boundary.getId(),
                    boundary.getBoundaryKey(),
                    boundary.getDisplayName(),
                    boundary.getDescription(),
                    boundary.getSource(),
                    boundary.getPathSelectors(),
                    boundary.getSurfaces(),
                    boundary.getInputFactKeys());
        }
    }

    public record AssignmentResponse(
            UUID id,
            String boundaryKey,
            String sourceFactKey,
            String sourceFactKind,
            String sourcePath,
            String strategy) {

        static AssignmentResponse from(BoundaryModelAssignment assignment) {
            return new AssignmentResponse(
                    assignment.getId(),
                    assignment.getBoundary().getBoundaryKey(),
                    assignment.getSourceFactKey(),
                    assignment.getSourceFactKind(),
                    assignment.getSourcePath(),
                    assignment.getStrategy());
        }
    }

    public record GapResponse(
            UUID id, String sourceFactKey, String sourceFactKind, String sourcePath, String reason, String detail) {

        static GapResponse from(BoundaryModelGap gap) {
            return new GapResponse(
                    gap.getId(),
                    gap.getSourceFactKey(),
                    gap.getSourceFactKind(),
                    gap.getSourcePath(),
                    gap.getReason(),
                    gap.getDetail());
        }
    }
}
