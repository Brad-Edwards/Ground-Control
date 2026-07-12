package com.keplerops.groundcontrol.domain.workflowexecution.audit;

/**
 * Whether a workflow operator-signal attempt was authorized (GC-O009 (b), GC-P024). Recorded on every
 * {@link OperatorSignalAudit} row so the gate-authority trail captures denied attempts, not just the
 * signals that reached the workflow.
 */
public enum AuthorizationOutcome {
    ALLOWED,
    DENIED
}
