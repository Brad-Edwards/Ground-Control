package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchProvenanceEdge}. */
public record ProvenanceEdgeResponse(
        UUID id,
        UUID fromNodeId,
        UUID toNodeId,
        ProvenanceEdgeRelation relation,
        String role,
        String summary,
        ProvenanceRecordStatus status,
        UUID supersededByEdgeId,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ProvenanceEdgeResponse from(ResearchProvenanceEdge e) {
        return new ProvenanceEdgeResponse(
                e.getId(),
                e.getFromNodeId(),
                e.getToNodeId(),
                e.getRelation(),
                e.getRole(),
                e.getSummary(),
                e.getStatus(),
                e.getSupersededByEdgeId(),
                e.getActor(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
