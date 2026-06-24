package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Terminal-or-current lifecycle state of a workflow run (issue #859).
 *
 * <p>This is a reporting projection, not a gate state machine: the value is whatever the bridge or
 * Temporal Visibility last observed for the run. It never drives workflow execution (ADR-028).
 */
public enum WorkflowRunState {
    RUNNING,
    READY_FOR_REVIEW,
    MERGED,
    CLOSED,
    ESCALATED,
    ABANDONED,
    SUPERSEDED
}
