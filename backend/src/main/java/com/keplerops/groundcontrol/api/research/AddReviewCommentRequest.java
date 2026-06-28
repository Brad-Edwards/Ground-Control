package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.service.AddReviewCommentCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Add a run-scoped review comment (GC-RSCH-F034, ADR-067). The author actor is
 * taken from the authenticated server context, not the request body (ADR-026).
 */
public record AddReviewCommentRequest(
        @NotNull ReviewCommentTarget targetType,
        ResearchGatePoint targetGatePoint,
        ResearchRunStage targetStage,
        UUID targetArtifactId,
        UUID targetDecisionLogId,
        @NotNull @Size(max = 2000) String body,
        @NotNull ReviewCommentProvenance provenance) {

    public AddReviewCommentCommand toCommand() {
        return new AddReviewCommentCommand(
                targetType, targetGatePoint, targetStage, targetArtifactId, targetDecisionLogId, body, provenance);
    }
}
