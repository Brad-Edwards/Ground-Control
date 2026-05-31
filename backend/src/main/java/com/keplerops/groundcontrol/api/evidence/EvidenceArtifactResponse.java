package com.keplerops.groundcontrol.api.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceArtifactResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String uid,
        String title,
        String summary,
        EvidenceType evidenceType,
        String derivationMethod,
        Instant derivedAt,
        String derivedBy,
        AssuranceLevel assuranceLevel,
        String confidence,
        String notes,
        UUID supersededByArtifactId,
        List<EvidenceSourceRefDto> sources,
        Instant expiresAt,
        Integer validityWindowDays,
        Boolean expired,
        Instant createdAt,
        Instant updatedAt) {

    public static EvidenceArtifactResponse from(EvidenceArtifact artifact) {
        return from(artifact, java.time.Instant.now());
    }

    public static EvidenceArtifactResponse from(EvidenceArtifact artifact, Instant asOf) {
        List<EvidenceSourceRefDto> sourceDtos = artifact.getSources() == null
                ? List.of()
                : artifact.getSources().stream()
                        .map(EvidenceSourceRefDto::fromDomain)
                        .toList();
        Boolean expired = artifact.getExpiresAt() == null ? null : artifact.isExpiredAt(asOf);
        return new EvidenceArtifactResponse(
                artifact.getId(),
                GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, artifact.getId()),
                artifact.getProject().getIdentifier(),
                artifact.getUid(),
                artifact.getTitle(),
                artifact.getSummary(),
                artifact.getEvidenceType(),
                artifact.getDerivationMethod(),
                artifact.getDerivedAt(),
                artifact.getDerivedBy(),
                artifact.getAssuranceLevel(),
                artifact.getConfidence(),
                artifact.getNotes(),
                artifact.getSupersededByArtifactId(),
                sourceDtos,
                artifact.getExpiresAt(),
                artifact.getValidityWindowDays(),
                expired,
                artifact.getCreatedAt(),
                artifact.getUpdatedAt());
    }
}
