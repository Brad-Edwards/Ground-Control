package com.keplerops.groundcontrol.api.documents;

import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String title,
        String version,
        String description,
        boolean hasGrammar,
        Instant createdAt,
        Instant updatedAt,
        String createdBy) {

    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                GraphIds.nodeId(GraphEntityType.DOCUMENT, doc.getId()),
                doc.getProject().getIdentifier(),
                doc.getTitle(),
                doc.getVersion(),
                doc.getDescription(),
                doc.getGrammar() != null,
                doc.getCreatedAt(),
                doc.getUpdatedAt(),
                doc.getCreatedBy());
    }
}
