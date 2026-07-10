package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;

/** Response for a started execution: its stable workflow id and started run id. */
public record WorkflowExecutionStartResponse(
        String workflowId, String runId, WorkflowType workflowType, String project) {

    public static WorkflowExecutionStartResponse from(WorkflowExecutionRef ref) {
        return new WorkflowExecutionStartResponse(ref.workflowId(), ref.runId(), ref.workflowType(), ref.project());
    }
}
