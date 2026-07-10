package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/workflow-executions}. Closed, redacted field set — no prompts,
 * completions, tokens, or raw payloads. {@code project} is a query parameter.
 *
 * <p>The workflow completion command is deliberately NOT a field here: it is the automation command
 * the worker executes, so it is derived from server-side configuration only. Accepting it from the
 * caller would turn this control API into an arbitrary-command-execution primitive.
 */
public record StartWorkflowExecutionRequest(
        @NotNull WorkflowType workflowType,
        @NotNull @Positive Integer issueNumber,
        @Size(max = 200) String sonarProjectKey,
        @Min(1) @Max(10) Integer reviewCap,
        List<@Size(max = 100) String> requirementUids,
        @Min(1) @Max(86400) Integer pollIntervalSeconds) {}
