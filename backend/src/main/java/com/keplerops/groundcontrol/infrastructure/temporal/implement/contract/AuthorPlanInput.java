package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/**
 * Activity payload. Schema: {@code gc.workflow.content-activities.v2#/$defs/AuthorPlanInput}.
 *
 * <p>{@code v2} (issue #1280): adds required {@code project} and {@code route} so {@code authorPlan}
 * can resolve project-scoped LLM access without inferring project ownership from the issue number, a
 * local checkout, or the workflow id. {@code route} is bound to the execution at start time
 * (ADR-028) — never re-resolved from a mutable feature-branch config on activity retry.
 */
public record AuthorPlanInput(
        String project, ResolvedLlmRoute route, int issueNumber, List<String> requirementUids, String idempotencyKey) {

    public AuthorPlanInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
