package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link ResearchRunReviewComment} (GC-RSCH-F034, ADR-067).
 * DTOs (not the controller) name the domain enums (ArchUnit boundary).
 */
public record ResearchRunReviewCommentResponse(
        UUID id,
        ReviewCommentTarget targetType,
        ResearchGatePoint targetGatePoint,
        ResearchRunStage targetStage,
        UUID targetArtifactId,
        UUID targetDecisionLogId,
        String body,
        ReviewCommentProvenance provenance,
        ReviewCommentStatus status,
        String resolutionSummary,
        String authorActor,
        String resolvedByActor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunReviewCommentResponse from(ResearchRunReviewComment c) {
        return new ResearchRunReviewCommentResponse(
                c.getId(),
                c.getTargetType(),
                c.getTargetGatePoint(),
                c.getTargetStage(),
                c.getTargetArtifactId(),
                c.getTargetDecisionLogId(),
                c.getBody(),
                c.getProvenance(),
                c.getStatus(),
                c.getResolutionSummary(),
                c.getAuthorActor(),
                c.getResolvedByActor(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
