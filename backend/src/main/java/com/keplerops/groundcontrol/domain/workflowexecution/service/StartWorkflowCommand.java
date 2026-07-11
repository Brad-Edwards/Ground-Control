package com.keplerops.groundcontrol.domain.workflowexecution.service;

import com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import java.util.List;

/**
 * Validated inputs to start a workflow execution. The {@code workflowId} is built by the service from
 * the project-scoped {@link com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionId}
 * scheme so the infrastructure adapter is a pure executor and the id/scope convention stays in the
 * domain.
 *
 * <p>{@code route} (issue #1280, ADR-028) is the safe LLM route resolved once by
 * {@code WorkflowExecutionService} before {@code WorkflowControlPort.start}; the adapter binds it to
 * the started execution's durable input rather than re-resolving it later.
 */
public record StartWorkflowCommand(
        String workflowId,
        WorkflowType workflowType,
        String project,
        int issueNumber,
        String sonarProjectKey,
        Integer reviewCap,
        List<String> requirementUids,
        Integer pollIntervalSeconds,
        ResolvedLlmRoute route) {

    public StartWorkflowCommand {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
