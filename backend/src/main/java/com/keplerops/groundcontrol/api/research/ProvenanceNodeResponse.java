package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchProvenanceNode}. Bounded references and summaries only. */
public record ProvenanceNodeResponse(
        UUID id,
        ProvenanceNodeKind kind,
        String subjectKey,
        ResearchRunStage stage,
        ResearchArtifactType artifactType,
        UUID artifactId,
        Integer attemptNo,
        String locator,
        String contentHash,
        String externalIdentifier,
        String summary,
        String toolName,
        String toolVersion,
        String sourceActionId,
        ProvenanceRecordStatus status,
        UUID supersededByNodeId,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ProvenanceNodeResponse from(ResearchProvenanceNode n) {
        return new ProvenanceNodeResponse(
                n.getId(),
                n.getKind(),
                n.getSubjectKey(),
                n.getStage(),
                n.getArtifactType(),
                n.getArtifactId(),
                n.getAttemptNo(),
                n.getLocator(),
                n.getContentHash(),
                n.getExternalIdentifier(),
                n.getSummary(),
                n.getToolName(),
                n.getToolVersion(),
                n.getSourceActionId(),
                n.getStatus(),
                n.getSupersededByNodeId(),
                n.getActor(),
                n.getCreatedAt(),
                n.getUpdatedAt());
    }
}
