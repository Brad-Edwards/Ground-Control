package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F034 / ADR-067 — lifecycle state of a {@link ResearchRunReviewComment}.
 * A comment opens {@code OPEN} and moves to {@code RESOLVED} once addressed; it
 * may be reopened.
 */
public enum ReviewCommentStatus {
    OPEN,
    RESOLVED
}
