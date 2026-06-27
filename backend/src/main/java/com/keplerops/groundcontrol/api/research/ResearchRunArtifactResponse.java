package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchRunArtifact} manifest row. */
public record ResearchRunArtifactResponse(
        UUID id,
        ResearchRunStage stage,
        ResearchArtifactType artifactType,
        ResearchArtifactStatus status,
        String locator,
        String contentHash,
        String idempotencyKey,
        int attemptNo,
        UUID supersededByArtifactId,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunArtifactResponse from(ResearchRunArtifact a) {
        return new ResearchRunArtifactResponse(
                a.getId(),
                a.getStage(),
                a.getArtifactType(),
                a.getStatus(),
                a.getLocator(),
                a.getContentHash(),
                a.getIdempotencyKey(),
                a.getAttemptNo(),
                a.getSupersededByArtifactId(),
                a.getActor(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
