package com.keplerops.groundcontrol.infrastructure.temporal.smoke;

import io.temporal.workflow.Workflow;

public class TemporalSmokeWorkflowImpl implements TemporalSmokeWorkflow {

    private boolean waiting;
    private String partition;

    @Override
    public String run(String projectIdentifier) {
        waiting = true;
        Workflow.await(() -> partition != null);
        return projectIdentifier + ":" + partition;
    }

    @Override
    public void complete(String partition) {
        this.partition = partition;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }
}
