package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.merged-artifacts.v1#/$defs/MergedArtifactsResult}. */
public record MergedArtifactsResult(List<String> implementsArtifacts, List<String> testsArtifacts) {

    public MergedArtifactsResult {
        implementsArtifacts = implementsArtifacts == null ? List.of() : List.copyOf(implementsArtifacts);
        testsArtifacts = testsArtifacts == null ? List.of() : List.copyOf(testsArtifacts);
    }
}
