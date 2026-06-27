package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read projection of a {@link WorkflowRun} — the closed, redacted run shape exposed over REST. */
public record WorkflowRunResponse(
        UUID id,
        String project,
        String repo,
        Integer issueNumber,
        Integer prNumber,
        String branch,
        String workflowType,
        String runtimeDriver,
        List<String> requirementUids,
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
        Long tokenUsage,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkflowRunResponse from(WorkflowRun run) {
        return new WorkflowRunResponse(
                run.getId(),
                run.getProject(),
                run.getRepo(),
                run.getIssueNumber(),
                run.getPrNumber(),
                run.getBranch(),
                run.getWorkflowType(),
                run.getRuntimeDriver(),
                List.copyOf(run.getRequirementUids()),
                run.getStartedAt(),
                run.getEndedAt(),
                run.getFinalState(),
                run.getOutcome(),
                run.getProvenance(),
                run.getProvider(),
                run.getModel(),
                run.getModelInvocationCount(),
                run.getWallClockMinutes(),
                run.getCostProxy(),
                run.getCostCurrency(),
                run.getTokenUsage(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
