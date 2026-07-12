package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Closed operator-signal catalog the control surface accepts (GC-O009 clause (b), ADR-028).
 *
 * <p>PR merge is observed from GitHub and is deliberately <strong>not</strong> a signal; there is no
 * plan-approval signal. Each value maps 1:1 to a {@code @SignalMethod} on the {@code ImplementWorkflow}
 * contract in the infrastructure adapter.
 */
public enum OperatorSignalType {
    /** Cancel the run; requires {@code reason}. */
    CANCEL,
    /** Authorize a retry from a phase band; requires {@code retryFromPhase}. */
    RETRY_FROM,
    /** Apply a review-cap-boundary disposition; requires {@code reviewer} and {@code disposition}. */
    REVIEW_CAP_DISPOSITION
}
