package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F034 / ADR-067 — the surface a {@link ResearchRunReviewComment} is
 * attached to. Exactly one target discriminator (gate point, stage, artifact, or
 * decision log) is populated per comment, selected by this type.
 */
public enum ReviewCommentTarget {
    RUN,
    GATE_POINT,
    STAGE,
    ARTIFACT,
    DECISION_LOG
}
