package com.keplerops.groundcontrol.api.dataclassification;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationResult;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationFinding;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;
import java.util.UUID;

/**
 * API response for a deterministic data-classification lattice evaluation (GC-GRC-006). Violations
 * are policy-violating flows; limitations are flows the lattice could not decide (missing/unknown
 * labels or dangling endpoints).
 */
public record DataClassificationEvaluationResponse(
        String projectIdentifier,
        String schemaVersion,
        String policyVersion,
        DataClassificationSource source,
        String modelVersion,
        UUID snapshotId,
        int evaluatedFlowCount,
        int violationCount,
        int limitationCount,
        List<FindingResponse> violations,
        List<FindingResponse> limitations) {

    public static DataClassificationEvaluationResponse from(
            String projectIdentifier, DataClassificationEvaluationResult result) {
        var violations = result.violations().stream().map(FindingResponse::from).toList();
        var limitations =
                result.limitations().stream().map(FindingResponse::from).toList();
        return new DataClassificationEvaluationResponse(
                projectIdentifier,
                result.schemaVersion(),
                result.policyVersion(),
                result.source(),
                result.modelVersion(),
                result.snapshotId(),
                result.evaluatedFlowCount(),
                violations.size(),
                limitations.size(),
                violations,
                limitations);
    }

    public record FindingResponse(
            String flowStableKey,
            String sourceStableKey,
            String sinkStableKey,
            String sourceLabelKey,
            String sinkLabelKey,
            String reason,
            String detail) {

        public static FindingResponse from(DataClassificationFinding finding) {
            return new FindingResponse(
                    finding.flowStableKey(),
                    finding.sourceStableKey(),
                    finding.sinkStableKey(),
                    finding.sourceLabelKey(),
                    finding.sinkLabelKey(),
                    finding.reason().name(),
                    finding.detail());
        }
    }
}
