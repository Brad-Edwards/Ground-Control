package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.ci-observation.v1#/$defs/CiObservationInput}. */
public record CiObservationInput(RepositoryBinding repository, int prNumber) {}
