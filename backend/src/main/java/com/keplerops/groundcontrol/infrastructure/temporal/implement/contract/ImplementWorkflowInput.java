package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/**
 * Activity payload. Schema: {@code gc.workflow.implement-workflow.v1#/$defs/ImplementWorkflowInput}.
 *
 * <p>{@code route} (issue #1280, ADR-028) is an additive, optional field: the safe LLM route resolved
 * and bound to this execution once, before {@code WorkflowControlPort.start}, so a change under
 * implementation cannot redirect its own source/context to another provider or increase its model
 * spend by influencing route resolution on an activity retry. {@code null} means route resolution is
 * unavailable/not applicable for this execution.
 */
public record ImplementWorkflowInput(
        String project,
        int issueNumber,
        String completionCommand,
        String sonarProjectKey,
        Integer reviewCap,
        List<String> requirementUids,
        Integer pollIntervalSeconds,
        ResolvedLlmRoute route) {

    public ImplementWorkflowInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
