package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Operator retry-from-phase signal payload. Schema: {@code gc.workflow.implement-signals.v1#/$defs/RetryFromSignal}. */
public record RetryFromSignal(ImplementPhase phase, String reason) {}
