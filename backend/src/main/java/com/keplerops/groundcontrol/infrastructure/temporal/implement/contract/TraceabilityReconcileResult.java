package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.traceability-reconcile.v1#/$defs/TraceabilityReconcileResult}. */
public record TraceabilityReconcileResult(int implementsLinksCreated, int testsLinksCreated) {}
