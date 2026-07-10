package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Reviewer a review-cap disposition signal targets (GC-O007 review loop). Product-surface mirror of the
 * Temporal-history {@code ReviewerKind}; mapped 1:1 by the infrastructure adapter.
 */
public enum Reviewer {
    CODEX,
    TEST_QUALITY
}
