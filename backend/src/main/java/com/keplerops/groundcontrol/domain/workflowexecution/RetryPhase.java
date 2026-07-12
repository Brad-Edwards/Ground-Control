package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Product-surface view of the {@code /implement} phase bands an operator may retry from (GC-O007 A–E).
 *
 * <p>Kept in the domain because {@code api}/{@code domain} cannot import the Temporal-history contract
 * enum {@code ImplementPhase} (ArchUnit boundary). The infrastructure adapter maps these values 1:1 to
 * {@code ImplementPhase} and an exhaustive round-trip test fails on any drift — the two enums are two
 * distinct contracts (product API vs. Temporal history), not accidental duplication.
 */
public enum RetryPhase {
    A_PLAN_IMPLEMENT,
    B_QUALITY_GATE,
    C_STAGE_COMMIT_PUSH,
    D_SHIP_PIPELINE,
    E_POST_MERGE_RECONCILE
}
