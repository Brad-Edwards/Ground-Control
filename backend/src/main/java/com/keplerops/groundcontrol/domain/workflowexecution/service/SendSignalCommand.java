package com.keplerops.groundcontrol.domain.workflowexecution.service;

import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;

/**
 * A validated operator signal to send to a workflow execution. Only the fields required by
 * {@code type} are populated; the service rejects the request when a required field is missing.
 */
public record SendSignalCommand(
        OperatorSignalType type,
        String reason,
        RetryPhase retryFromPhase,
        Reviewer reviewer,
        SignalDisposition disposition) {}
