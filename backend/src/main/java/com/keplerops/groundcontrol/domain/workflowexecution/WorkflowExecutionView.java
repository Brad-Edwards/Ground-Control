package com.keplerops.groundcontrol.domain.workflowexecution;

import java.time.Instant;
import java.util.List;

/**
 * Bounded, redacted product read model of one workflow execution, projected from Temporal Visibility
 * plus correlation data carried in the execution's Memo (ADR-028). Ids and enums only — never prompts,
 * completions, secrets, or provider payloads.
 *
 * @param correlation ids echoed back from the execution's Memo; may be partially null for executions
 *     started outside this surface.
 * @param gateState bounded gate-state read model for the operations console (GC-Q016), queried from the
 *     workflow on single-execution describe; {@code null} for bulk list entries and for executions whose
 *     gate state cannot be queried (closed executions, no live worker).
 */
public record WorkflowExecutionView(
        String workflowId,
        String runId,
        WorkflowType workflowType,
        WorkflowExecutionStatus status,
        Instant startTime,
        Instant closeTime,
        long historyLength,
        Correlation correlation,
        GateState gateState) {

    /** Non-secret correlation ids stored in the execution Memo at start time. */
    public record Correlation(String project, Integer issueNumber, List<String> requirementUids) {
        public Correlation {
            requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
        }
    }

    /**
     * Bounded gate-state read model (GC-O009 (c), GC-Q016 (a)/(b)): current phase, whether the run is
     * blocked on the single human merge gate, and which gate — if any — is escalated awaiting an operator
     * signal. Product enums only; mirrors the workflow's {@code GateState} query result.
     *
     * @param phase current execution phase (product mirror of the phase bands)
     * @param outcome interim/terminal outcome, or {@code null} while mid-phase
     * @param waitingForMerge {@code true} while blocked on the PR-merge human gate
     * @param escalatedPhase phase whose gate is escalated awaiting an operator signal, or {@code null}
     * @param escalatedReviewer reviewer whose review gate is escalated, or {@code null}
     */
    public record GateState(
            RetryPhase phase,
            WorkflowOutcome outcome,
            boolean waitingForMerge,
            RetryPhase escalatedPhase,
            Reviewer escalatedReviewer) {}
}
