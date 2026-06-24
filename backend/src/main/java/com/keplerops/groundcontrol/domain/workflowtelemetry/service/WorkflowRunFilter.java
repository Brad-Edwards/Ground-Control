package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import java.time.Instant;

/**
 * Scope for an aggregate read. {@code project} is null only for the admin cross-project rollup; all
 * other dimensions are optional filters the issue names (repo, runtime/agent, requirement, workflow
 * type, outcome) plus the {@code [from, to)} window.
 */
public record WorkflowRunFilter(
        Instant from,
        Instant to,
        String project,
        String repo,
        String workflowType,
        String runtime,
        WorkflowRunOutcome outcome,
        String requirement) {}
