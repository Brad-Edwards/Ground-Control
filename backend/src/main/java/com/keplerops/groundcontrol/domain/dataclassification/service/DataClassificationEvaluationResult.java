package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic result of evaluating an architecture-model snapshot's flows against a project's data
 * classification lattice (GC-GRC-006). Carries the policy and model versions it was computed against
 * so the finding is reproducible from stored policy + stored label assignments without any LLM. A
 * null {@code modelVersion}/{@code snapshotId} means there was no snapshot to evaluate.
 */
public record DataClassificationEvaluationResult(
        String schemaVersion,
        String policyVersion,
        DataClassificationSource source,
        String modelVersion,
        UUID snapshotId,
        int evaluatedFlowCount,
        List<DataClassificationFinding> violations,
        List<DataClassificationFinding> limitations) {

    public DataClassificationEvaluationResult {
        violations = List.copyOf(violations);
        limitations = List.copyOf(limitations);
    }
}
