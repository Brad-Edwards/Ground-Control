package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R005 / ADR-086 §1 — closed vocabulary of research high-risk operation
 * kinds. Each value is a concrete effect request, not a tool label: a tool being
 * allowed in inventory ({@code ResearchIntake.allowedTools}) never by itself
 * authorizes one of these effects. Adding a kind is an API-visible contract
 * change; the CHECK constraint on {@code research_run_operation_authorization}
 * backstops this closed set.
 */
public enum ResearchHighRiskOperationKind {
    GENERATED_CODE_EXECUTION,
    BROWSER_ACTIVITY,
    LAB_HARDWARE_ACTION,
    EXTERNAL_WRITE
}
