package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Product-surface view of a {@code /implement} run's outcome (GC-O009, GC-Q016). Product mirror of the
 * Temporal-history contract enum {@code ImplementOutcome}; the infrastructure adapter maps the two 1:1.
 * Kept in the domain because {@code api}/{@code domain} cannot import the Temporal-history contract
 * (ArchUnit boundary).
 *
 * <p>{@code READY_FOR_REVIEW} is the Phase D terminal signal (the run is blocked on the single human
 * merge gate); {@code ESCALATED} means a gate is paused awaiting an operator signal.
 */
public enum WorkflowOutcome {
    READY_FOR_REVIEW,
    MERGED,
    ESCALATED,
    CANCELLED
}
