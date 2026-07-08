package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.merge-observation.v1#/$defs/MergeObservationInput}. */
public record MergeObservationInput(RepositoryBinding repository, int prNumber) {}
