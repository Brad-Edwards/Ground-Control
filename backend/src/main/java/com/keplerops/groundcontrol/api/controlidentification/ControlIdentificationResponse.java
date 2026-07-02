package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationGap;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationResult;
import java.util.List;

/**
 * API response for a deterministic control-identification run (GC-GRC-008). Candidates and gaps are
 * ordered for byte-stable serialization across repeated calls with the same inputs.
 */
public record ControlIdentificationResponse(
        String projectIdentifier,
        String schemaVersion,
        String ruleSetId,
        String ruleSetVersion,
        int candidateCount,
        int gapCount,
        List<ControlCandidateResponse> candidates,
        List<GapResponse> gaps) {

    public static ControlIdentificationResponse from(String projectIdentifier, ControlIdentificationResult result) {
        var candidates =
                result.candidates().stream().map(ControlCandidateResponse::from).toList();
        var gaps = result.gaps().stream().map(GapResponse::from).toList();
        return new ControlIdentificationResponse(
                projectIdentifier,
                result.schemaVersion(),
                result.ruleSetId(),
                result.ruleSetVersion(),
                candidates.size(),
                gaps.size(),
                candidates,
                gaps);
    }

    /** API response DTO for a single control-design gap. */
    public record GapResponse(
            String threatCategory,
            String strideCategory,
            String objectiveKey,
            String producingRuleId,
            String threatRef,
            String reason,
            String description) {

        public static GapResponse from(ControlIdentificationGap gap) {
            return new GapResponse(
                    gap.threatCategory() != null ? gap.threatCategory().name() : null,
                    gap.strideCategory() != null ? gap.strideCategory().name() : null,
                    gap.objectiveKey(),
                    gap.producingRuleId(),
                    gap.threatRef(),
                    gap.reason() != null ? gap.reason().name() : null,
                    gap.description());
        }
    }
}
