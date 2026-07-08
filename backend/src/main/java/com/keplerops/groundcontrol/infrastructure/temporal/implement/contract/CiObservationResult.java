package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.ci-observation.v1#/$defs/CiObservationResult}. */
public record CiObservationResult(CiState state, List<String> failedChecks) {

    public CiObservationResult {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
    }
}
