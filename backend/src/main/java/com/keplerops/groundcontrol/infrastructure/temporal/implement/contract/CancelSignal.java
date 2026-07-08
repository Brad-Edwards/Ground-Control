package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Operator cancel signal payload. Schema: {@code gc.workflow.implement-signals.v1#/$defs/CancelSignal}. */
public record CancelSignal(String reason) {}
