package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Product-surface view of a Temporal execution's lifecycle status, read from Temporal Visibility
 * (ADR-028). The infrastructure adapter maps the Temporal {@code WorkflowExecutionStatus} proto enum
 * to these values; unrecognised/unspecified statuses map to {@link #UNKNOWN} so a new server-side
 * status never breaks the read model.
 */
public enum WorkflowExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    TERMINATED,
    CONTINUED_AS_NEW,
    TIMED_OUT,
    PAUSED,
    UNKNOWN
}
