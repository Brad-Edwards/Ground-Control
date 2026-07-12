package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Execution phase of the deterministic {@code /implement} workflow (GC-O007 phase graph A-E).
 *
 * <p>This is the workflow's execution vocabulary. It is deliberately distinct from
 * {@link com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState}, which is an
 * ADR-061 reporting projection that "never drives workflow execution". Temporal history remains the
 * source of truth for progress; this enum only labels which contractual gate band the workflow is in.
 */
public enum ImplementPhase {
    A_PLAN_IMPLEMENT,
    B_QUALITY_GATE,
    C_STAGE_COMMIT_PUSH,
    D_SHIP_PIPELINE,
    E_POST_MERGE_RECONCILE
}
