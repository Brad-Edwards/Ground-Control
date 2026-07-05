package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.quality-gate.v1#/$defs/QualityGateResult}. */
public record QualityGateResult(boolean passed, List<String> failedGates) {

    public QualityGateResult {
        failedGates = failedGates == null ? List.of() : List.copyOf(failedGates);
    }
}
