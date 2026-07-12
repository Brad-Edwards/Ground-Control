package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.completion-gate.v1#/$defs/CompletionGateInput}. */
public record CompletionGateInput(String command) {}
