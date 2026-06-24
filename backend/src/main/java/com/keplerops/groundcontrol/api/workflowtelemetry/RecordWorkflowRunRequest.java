package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Request body for {@code POST /api/v1/workflow-runs}. The {@code project} is a query parameter; this
 * is the closed, redacted field set — no prompts, completions, tokens, or raw payloads are accepted.
 */
public record RecordWorkflowRunRequest(
        @Size(max = 200) String repo,
        Integer issueNumber,
        Integer prNumber,
        @Size(max = 300) String branch,
        @NotBlank @Size(max = 100) String workflowType,
        @Size(max = 100) String runtimeDriver,
        Set<@Size(max = 100) String> requirementUids,
        Instant startedAt,
        Instant endedAt,
        WorkflowRunState finalState,
        WorkflowRunOutcome outcome,
        @NotNull TelemetryProvenance provenance,
        @Size(max = 100) String provider,
        @Size(max = 200) String model,
        @PositiveOrZero Integer modelInvocationCount,
        @PositiveOrZero Integer wallClockMinutes,
        @PositiveOrZero @Digits(integer = 10, fraction = 4) BigDecimal costProxy,
        @Size(max = 10) String costCurrency,
        @PositiveOrZero Long tokenUsage) {}
