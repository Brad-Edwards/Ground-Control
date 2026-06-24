package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Immutable command to record (upsert) one workflow run. Carries only the closed, redacted field
 * set — no prompts, completions, tokens, or raw payloads. The idempotency key is
 * {@code (project, repo, issueNumber, branch)}.
 */
public record RecordWorkflowRunCommand(
        String project,
        String repo,
        Integer issueNumber,
        Integer prNumber,
        String branch,
        String workflowType,
        String runtimeDriver,
        Set<String> requirementUids,
        Instant startedAt,
        Instant endedAt,
        WorkflowRunState finalState,
        WorkflowRunOutcome outcome,
        TelemetryProvenance provenance,
        String provider,
        String model,
        Integer modelInvocationCount,
        Integer wallClockMinutes,
        BigDecimal costProxy,
        String costCurrency,
        Long tokenUsage) {}
