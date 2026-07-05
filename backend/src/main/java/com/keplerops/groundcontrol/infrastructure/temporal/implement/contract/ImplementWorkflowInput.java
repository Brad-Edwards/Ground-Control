package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.implement-workflow.v1#/$defs/ImplementWorkflowInput}. */
public record ImplementWorkflowInput(
        String project,
        int issueNumber,
        String completionCommand,
        String sonarProjectKey,
        Integer reviewCap,
        List<String> requirementUids,
        Integer pollIntervalSeconds) {

    public ImplementWorkflowInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
