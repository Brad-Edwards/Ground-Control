package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import java.util.UUID;

/**
 * Record (or rework) a provenance edge from an upstream input node to a
 * downstream output node (ADR-069 §2). Both endpoints must belong to the same
 * run; self-edges and cycles are rejected by the service.
 */
public record RecordProvenanceEdgeCommand(
        UUID fromNodeId,
        UUID toNodeId,
        ProvenanceEdgeRelation relation,
        String role,
        String summary,
        String idempotencyKey) {}
