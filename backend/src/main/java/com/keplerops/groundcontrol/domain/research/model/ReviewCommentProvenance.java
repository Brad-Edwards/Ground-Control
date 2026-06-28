package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F034 / ADR-067 — origin of a {@link ResearchRunReviewComment}.
 * Distinguishes a human reviewer's note from an agent recommendation and from an
 * automated system check.
 */
public enum ReviewCommentProvenance {
    HUMAN_REVIEW,
    AGENT_RECOMMENDATION,
    SYSTEM_CHECK
}
