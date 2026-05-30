package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain result for the Traceability Matrix per GC-Q003.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. Requirements form the rows; their outbound
 * {@code TraceabilityLink}s form the cells, projected per {@link LinkType}. Column summaries roll
 * coverage up across the in-scope rows.
 *
 * <p><strong>Gap interpretation (GC-Q003 "is every requirement implemented and tested?").</strong>
 * The two coverage axes are {@link LinkType#IMPLEMENTS} and {@link LinkType#TESTS}. A row is flagged
 * {@code hasGap} only when its status is {@link Status#ACTIVE} and it is missing one of the in-scope
 * coverage axes. Non-ACTIVE requirements are never flagged: an {@code IMPLEMENTS} link cannot exist
 * until a requirement is ACTIVE (enforced by {@code TraceabilityService.createLink}), so flagging a
 * DRAFT requirement as a gap would be noise rather than signal. When a single {@code linkType} filter
 * is applied, that one type becomes the sole coverage axis.
 */
public record TraceabilityMatrixResult(
        List<MatrixRow> rows,
        List<LinkTypeColumn> columns,
        int requirementCount,
        int linkedRequirementCount,
        int gapCount) {

    /**
     * One requirement row with its projected cells, the distinct link types it covers, and a gap flag.
     */
    public record MatrixRow(
            UUID requirementId,
            String uid,
            String title,
            Status status,
            Integer wave,
            Priority priority,
            List<MatrixCell> cells,
            List<LinkType> coveredLinkTypes,
            boolean hasGap) {}

    /**
     * A single traceability link projected into a matrix cell. {@code artifactTitle} / {@code artifactUrl}
     * may be empty strings (the entity defaults), in which case the UI falls back to the identifier.
     */
    public record MatrixCell(
            UUID linkId,
            LinkType linkType,
            ArtifactType artifactType,
            String artifactIdentifier,
            String artifactTitle,
            String artifactUrl,
            SyncStatus syncStatus) {}

    /**
     * A per-{@link LinkType} coverage summary across the in-scope rows. {@code coveredRequirements} is the
     * number of rows with at least one link of this type; {@code artifactCount} is the total number of such
     * links (cells) across all rows.
     */
    public record LinkTypeColumn(
            LinkType linkType, int coveredRequirements, int totalRequirements, int artifactCount) {}
}
