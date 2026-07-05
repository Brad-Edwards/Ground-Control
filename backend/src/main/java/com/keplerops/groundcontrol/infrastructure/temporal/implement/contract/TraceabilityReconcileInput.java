package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

import java.util.List;

/** Activity payload. Schema: {@code gc.workflow.traceability-reconcile.v1#/$defs/TraceabilityReconcileInput}. */
public record TraceabilityReconcileInput(
        String project,
        List<String> requirementUids,
        int prNumber,
        List<String> implementsArtifacts,
        List<String> testsArtifacts,
        String idempotencyKey) {

    public TraceabilityReconcileInput {
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
        implementsArtifacts = implementsArtifacts == null ? List.of() : List.copyOf(implementsArtifacts);
        testsArtifacts = testsArtifacts == null ? List.of() : List.copyOf(testsArtifacts);
    }
}
