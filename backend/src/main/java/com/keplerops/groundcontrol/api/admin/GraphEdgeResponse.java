package com.keplerops.groundcontrol.api.admin;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import java.util.Map;

public record GraphEdgeResponse(
        String id,
        String edgeType,
        String sourceId,
        String targetId,
        GraphEntityType sourceEntityType,
        GraphEntityType targetEntityType,
        Map<String, Object> properties) {

    public static GraphEdgeResponse from(GraphEdge edge) {
        return new GraphEdgeResponse(
                edge.id(),
                edge.edgeType(),
                edge.sourceId(),
                edge.targetId(),
                edge.sourceEntityType(),
                edge.targetEntityType(),
                edge.properties());
    }
}
