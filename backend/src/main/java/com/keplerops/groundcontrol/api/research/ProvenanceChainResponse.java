package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.ProvenanceChain;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-R004 — read view of a bounded backward-provenance chain: the root node
 * plus every upstream node and edge that supports it.
 */
public record ProvenanceChainResponse(
        UUID rootNodeId,
        int maxDepth,
        boolean truncated,
        List<ProvenanceNodeResponse> nodes,
        List<ProvenanceEdgeResponse> edges) {

    public static ProvenanceChainResponse from(ProvenanceChain chain) {
        return new ProvenanceChainResponse(
                chain.rootNodeId(),
                chain.maxDepth(),
                chain.truncated(),
                chain.nodes().stream().map(ProvenanceNodeResponse::from).toList(),
                chain.edges().stream().map(ProvenanceEdgeResponse::from).toList());
    }
}
