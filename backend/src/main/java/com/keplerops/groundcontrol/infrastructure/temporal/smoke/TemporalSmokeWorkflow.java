package com.keplerops.groundcontrol.infrastructure.temporal.smoke;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TemporalSmokeWorkflow {

    @WorkflowMethod
    String run(String projectIdentifier);

    @SignalMethod
    void complete(String partition);

    @QueryMethod
    boolean isWaiting();
}
