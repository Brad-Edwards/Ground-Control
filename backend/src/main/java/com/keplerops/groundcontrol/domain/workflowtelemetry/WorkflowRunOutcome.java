package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Merge/close outcome of a workflow run (issue #859), used for cost-proxy-per-outcome reporting.
 */
public enum WorkflowRunOutcome {
    MERGED,
    CLOSED_WITHOUT_MERGE,
    NONE
}
