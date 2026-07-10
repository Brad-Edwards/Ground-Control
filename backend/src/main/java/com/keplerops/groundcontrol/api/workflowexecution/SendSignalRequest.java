package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflow-executions/{workflowId}/signals}. Only the fields
 * required by {@code signalType} need be present; the service enforces the per-signal contract and
 * returns a 422 envelope otherwise.
 */
public record SendSignalRequest(
        @NotNull OperatorSignalType signalType,
        @Size(max = 500) String reason,
        RetryPhase retryFromPhase,
        Reviewer reviewer,
        SignalDisposition disposition) {}
