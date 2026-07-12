package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowOutcome;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import java.time.Instant;
import java.util.List;

/** Read projection of a workflow execution exposed over REST — the bounded, redacted shape. */
public record WorkflowExecutionResponse(
        String workflowId,
        String runId,
        WorkflowType workflowType,
        WorkflowExecutionStatus status,
        Instant startTime,
        Instant closeTime,
        long historyLength,
        String project,
        Integer issueNumber,
        List<String> requirementUids,
        // Nullable: null for bulk list entries and executions whose gate state cannot be queried. The
        // generated TypeScript client types this as `GateStateResponse | null` via an explicit override
        // in tools/contracts/generate-contracts.mjs, because springdoc does not emit nullability for a
        // bare $ref (the same repo-wide limitation under which nullable scalars render untyped).
        GateStateResponse gateState) {

    /**
     * Bounded gate-state read model for the operations console (GC-Q016); {@code null} for bulk list
     * entries and executions whose gate state cannot be queried.
     */
    public record GateStateResponse(
            RetryPhase phase,
            WorkflowOutcome outcome,
            boolean waitingForMerge,
            RetryPhase escalatedPhase,
            Reviewer escalatedReviewer) {

        static GateStateResponse from(WorkflowExecutionView.GateState gateState) {
            if (gateState == null) {
                return null;
            }
            return new GateStateResponse(
                    gateState.phase(),
                    gateState.outcome(),
                    gateState.waitingForMerge(),
                    gateState.escalatedPhase(),
                    gateState.escalatedReviewer());
        }
    }

    public static WorkflowExecutionResponse from(WorkflowExecutionView view) {
        var correlation = view.correlation();
        return new WorkflowExecutionResponse(
                view.workflowId(),
                view.runId(),
                view.workflowType(),
                view.status(),
                view.startTime(),
                view.closeTime(),
                view.historyLength(),
                correlation == null ? null : correlation.project(),
                correlation == null ? null : correlation.issueNumber(),
                correlation == null ? List.of() : correlation.requirementUids(),
                GateStateResponse.from(view.gateState()));
    }
}
