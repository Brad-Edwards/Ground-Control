package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixResult;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.util.List;
import java.util.UUID;

/**
 * HTTP DTO mirror of {@link TraceabilityMatrixResult} for the
 * {@code GET /api/v1/requirements/traceability/matrix} endpoint (GC-Q003).
 */
public record TraceabilityMatrixResponse(
        List<MatrixRowDto> rows,
        List<LinkTypeColumnDto> columns,
        int requirementCount,
        int linkedRequirementCount,
        int gapCount) {

    public static TraceabilityMatrixResponse from(TraceabilityMatrixResult result) {
        List<MatrixRowDto> rows = result.rows().stream()
                .map(r -> new MatrixRowDto(
                        r.requirementId(),
                        r.uid(),
                        r.title(),
                        r.status(),
                        r.wave(),
                        r.priority(),
                        r.cells().stream()
                                .map(c -> new MatrixCellDto(
                                        c.linkId(),
                                        c.linkType(),
                                        c.artifactType(),
                                        c.artifactIdentifier(),
                                        c.artifactTitle(),
                                        c.artifactUrl(),
                                        c.syncStatus()))
                                .toList(),
                        r.coveredLinkTypes(),
                        r.hasGap()))
                .toList();
        List<LinkTypeColumnDto> columns = result.columns().stream()
                .map(c -> new LinkTypeColumnDto(
                        c.linkType(), c.coveredRequirements(), c.totalRequirements(), c.artifactCount()))
                .toList();
        return new TraceabilityMatrixResponse(
                rows, columns, result.requirementCount(), result.linkedRequirementCount(), result.gapCount());
    }

    public record MatrixRowDto(
            UUID requirementId,
            String uid,
            String title,
            Status status,
            Integer wave,
            Priority priority,
            List<MatrixCellDto> cells,
            List<LinkType> coveredLinkTypes,
            boolean hasGap) {}

    public record MatrixCellDto(
            UUID linkId,
            LinkType linkType,
            ArtifactType artifactType,
            String artifactIdentifier,
            String artifactTitle,
            String artifactUrl,
            SyncStatus syncStatus) {}

    public record LinkTypeColumnDto(
            LinkType linkType, int coveredRequirements, int totalRequirements, int artifactCount) {}
}
