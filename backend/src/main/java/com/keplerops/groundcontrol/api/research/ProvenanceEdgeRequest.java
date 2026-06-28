package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceEdgeCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Record (or rework) a provenance edge from an upstream input node to a
 * downstream output node (ADR-069 §2). Both endpoints must belong to the same
 * run; self-edges and cycles are rejected by the service.
 */
public record ProvenanceEdgeRequest(
        @NotNull UUID fromNodeId,
        @NotNull UUID toNodeId,
        @NotNull ProvenanceEdgeRelation relation,
        @Size(max = 200) String role,
        @Size(max = 2000) String summary,
        @Size(max = 200) String idempotencyKey) {

    public RecordProvenanceEdgeCommand toCommand() {
        return new RecordProvenanceEdgeCommand(fromNodeId, toNodeId, relation, role, summary, idempotencyKey);
    }
}
