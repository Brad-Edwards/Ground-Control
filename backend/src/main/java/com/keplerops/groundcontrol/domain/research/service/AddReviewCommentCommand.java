package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import java.util.UUID;

/**
 * Add a run-scoped review comment (GC-RSCH-F034, ADR-067). Exactly one target
 * discriminator must match {@code targetType}; {@code body} is a bounded note,
 * never raw manuscript prose. The author actor is taken from the authenticated
 * server context (ADR-026), not this command.
 */
public record AddReviewCommentCommand(
        ReviewCommentTarget targetType,
        ResearchGatePoint targetGatePoint,
        ResearchRunStage targetStage,
        UUID targetArtifactId,
        UUID targetDecisionLogId,
        String body,
        ReviewCommentProvenance provenance) {}
