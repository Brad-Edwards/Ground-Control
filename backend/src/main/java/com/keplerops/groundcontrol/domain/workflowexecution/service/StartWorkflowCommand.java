package com.keplerops.groundcontrol.domain.workflowexecution.service;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import java.util.List;

/**
 * Validated inputs to start a workflow execution. The {@code workflowId} is built by the service from
 * the project-scoped {@link com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionId}
 * scheme so the infrastructure adapter is a pure executor and the id/scope convention stays in the
 * domain.
 */
public record StartWorkflowCommand(
        String workflowId,
        WorkflowType workflowType,
        String project,
        int issueNumber,
        String sonarProjectKey,
        Integer reviewCap,
        List<String> requirementUids,
        Integer pollIntervalSeconds) {

    public StartWorkflowCommand {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
