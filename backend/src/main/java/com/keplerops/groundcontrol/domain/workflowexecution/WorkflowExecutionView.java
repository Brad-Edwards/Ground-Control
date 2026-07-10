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
 */
public record WorkflowExecutionView(
        String workflowId,
        String runId,
        WorkflowType workflowType,
        WorkflowExecutionStatus status,
        Instant startTime,
        Instant closeTime,
        long historyLength,
        Correlation correlation) {

    /** Non-secret correlation ids stored in the execution Memo at start time. */
    public record Correlation(String project, Integer issueNumber, List<String> requirementUids) {
        public Correlation {
            requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
        }
    }
}
