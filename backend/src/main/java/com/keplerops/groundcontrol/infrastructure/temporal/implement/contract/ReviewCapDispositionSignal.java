package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Operator review-cap-boundary disposition signal payload. Schema:
 * {@code gc.workflow.implement-signals.v1#/$defs/ReviewCapDispositionSignal}.
 */
public record ReviewCapDispositionSignal(ReviewerKind reviewer, CapDisposition disposition) {}
