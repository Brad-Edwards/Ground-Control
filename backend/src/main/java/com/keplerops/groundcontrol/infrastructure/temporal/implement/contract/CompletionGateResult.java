package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.completion-gate.v1#/$defs/CompletionGateResult}. */
public record CompletionGateResult(boolean passed, int exitCode, String summary) {}
