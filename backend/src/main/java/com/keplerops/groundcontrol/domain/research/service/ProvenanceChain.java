package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-R004 / ADR-069 §6 — bounded backward-provenance subgraph for one root
 * node. Answers "which sources and charted cells support this synthesis or draft
 * claim?": {@code nodes} is the root plus every upstream node reachable by
 * walking incoming edges, and {@code edges} are the traversed derivation edges.
 * {@code truncated} is true when the walk hit the depth cap before exhausting the
 * graph.
 */
public record ProvenanceChain(
        UUID rootNodeId,
        int maxDepth,
        boolean truncated,
        List<ResearchProvenanceNode> nodes,
        List<ResearchProvenanceEdge> edges) {}
