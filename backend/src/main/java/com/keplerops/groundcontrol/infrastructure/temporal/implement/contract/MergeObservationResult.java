package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.merge-observation.v1#/$defs/MergeObservationResult}. */
public record MergeObservationResult(boolean merged, PrState prState) {}
