package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.ResolveReviewCommentCommand;
import jakarta.validation.constraints.Size;

/**
 * Resolve a review comment (GC-RSCH-F034, ADR-067). The resolving actor is
 * taken from the authenticated server context, not the request body (ADR-026).
 */
public record ResolveReviewCommentRequest(@Size(max = 1000) String resolutionSummary) {

    public ResolveReviewCommentCommand toCommand() {
        return new ResolveReviewCommentCommand(resolutionSummary);
    }
}
