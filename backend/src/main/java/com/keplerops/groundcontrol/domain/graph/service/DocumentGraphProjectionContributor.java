package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentGraphProjectionContributor implements GraphProjectionContributor {

    private final DocumentRepository repository;

    public DocumentGraphProjectionContributor(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(DocumentGraphProjectionContributor::toNode)
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        return List.of();
    }

    private static GraphNode toNode(Document doc) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", doc.getTitle());
        properties.put("version", doc.getVersion());
        if (doc.getDescription() != null) {
            properties.put("description", doc.getDescription());
        }
        if (doc.getCreatedBy() != null) {
            properties.put("createdBy", doc.getCreatedBy());
        }
        properties.put("createdAt", doc.getCreatedAt().toString());
        if (doc.getUpdatedAt() != null) {
            properties.put("updatedAt", doc.getUpdatedAt().toString());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.DOCUMENT, doc.getId()),
                doc.getId().toString(),
                GraphEntityType.DOCUMENT,
                doc.getProject().getIdentifier(),
                null,
                doc.getTitle(),
                properties);
    }
}
