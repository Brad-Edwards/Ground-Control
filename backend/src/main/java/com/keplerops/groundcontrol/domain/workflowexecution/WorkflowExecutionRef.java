package com.keplerops.groundcontrol.domain.workflowexecution;

/** Identity of a started workflow execution: the stable workflow id and the started run id. */
public record WorkflowExecutionRef(String workflowId, String runId, WorkflowType workflowType, String project) {}
