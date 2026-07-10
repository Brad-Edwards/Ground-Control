package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
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
        List<String> requirementUids) {

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
                correlation == null ? List.of() : correlation.requirementUids());
    }
}
