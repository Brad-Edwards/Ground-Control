package com.keplerops.groundcontrol.api.admin;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import java.util.List;
import java.util.Map;

public record GraphVisualizationNodeResponse(
        String id,
        String domainId,
        GraphEntityType entityType,
        String projectIdentifier,
        String uid,
        String label,
        Map<String, Object> properties) {

    public static GraphVisualizationNodeResponse from(GraphNode node) {
        return new GraphVisualizationNodeResponse(
                node.id(),
                node.domainId(),
                node.entityType(),
                node.projectIdentifier(),
                node.uid(),
                node.label(),
                node.properties());
    }

    public static List<GraphVisualizationNodeResponse> from(GraphProjection projection) {
        return projection.nodes().stream()
                .map(GraphVisualizationNodeResponse::from)
                .toList();
    }
}
