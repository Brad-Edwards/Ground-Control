package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Terminal outcome of a deterministic {@code /implement} workflow run.
 *
 * <p>{@code READY_FOR_REVIEW} is the Phase D terminal signal (the PR is presented for the single
 * human merge gate); {@code MERGED} is reached only after Phase E post-merge reconciliation;
 * {@code ESCALATED} is a non-retryable gate failure awaiting operator input; {@code CANCELLED} is a
 * cancel signal short-circuit.
 */
public enum ImplementOutcome {
    READY_FOR_REVIEW,
    MERGED,
    ESCALATED,
    CANCELLED
}
