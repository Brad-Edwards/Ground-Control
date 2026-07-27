package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Terminal-or-current lifecycle state of a workflow run (issue #859).
 *
 * <p>This is a reporting projection, not a gate state machine: the value is whatever the bridge
 * last observed for the run. It never drives workflow execution.
 *
 * <p>{@link #RUNNING}, {@link #READY_FOR_REVIEW}, and {@link #ESCALATED} are open states: the run
 * may still advance, so none of them carries an end time. The remaining values are terminal and
 * carry {@code ended_at}. {@link #FAILED} was added by issue #1435 because the original vocabulary
 * could not tell a non-recoverable failure apart from an abandonment or a human escalation; it
 * records an explicitly observed failed run and is never inferred from a retryable phase failure.
 *
 * <p>{@code RUNNING} means "no terminal observation has been recorded", not proof that a process is
 * alive — an abrupt process or host death cannot execute a terminal write (ADR-061 #1435 amendment).
 */
public enum WorkflowRunState {
    RUNNING,
    READY_FOR_REVIEW,
    MERGED,
    CLOSED,
    ESCALATED,
    ABANDONED,
    SUPERSEDED,
    FAILED;

    /** True when the run has reached an end state and must not be reopened by a later observation. */
    public boolean isTerminal() {
        return this == MERGED || this == CLOSED || this == ABANDONED || this == SUPERSEDED || this == FAILED;
    }
}
