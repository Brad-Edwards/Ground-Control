package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Terminal result of the deterministic {@code /implement} workflow. Schema:
 * {@code gc.workflow.implement-workflow.v1#/$defs/ImplementWorkflowResult}.
 */
public record ImplementWorkflowResult(
        int issueNumber,
        ImplementPhase terminalPhase,
        ImplementOutcome outcome,
        Integer prNumber,
        boolean reconciled) {}
