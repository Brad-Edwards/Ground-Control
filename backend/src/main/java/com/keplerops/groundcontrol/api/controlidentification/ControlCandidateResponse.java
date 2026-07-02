package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlCandidate;
import java.util.Map;
import java.util.UUID;

/** API response DTO for a single candidate control (GC-GRC-008). */
public record ControlCandidateResponse(
        String producingRuleId,
        String ruleSetId,
        String ruleSetVersion,
        String threatCategory,
        String strideCategory,
        String objectiveKey,
        String threatRef,
        UUID controlId,
        String controlUid,
        String source,
        String packId,
        String packVersion,
        String packChecksum,
        String implementationGuidance,
        Map<String, String> matchedFacts,
        String rationale) {

    public static ControlCandidateResponse from(ControlCandidate candidate) {
        return new ControlCandidateResponse(
                candidate.producingRuleId(),
                candidate.ruleSetId(),
                candidate.ruleSetVersion(),
                candidate.threatCategory() != null ? candidate.threatCategory().name() : null,
                candidate.strideCategory() != null ? candidate.strideCategory().name() : null,
                candidate.objectiveKey(),
                candidate.threatRef(),
                candidate.controlId(),
                candidate.controlUid(),
                candidate.source() != null ? candidate.source().name() : null,
                candidate.packId(),
                candidate.packVersion(),
                candidate.packChecksum(),
                candidate.implementationGuidance(),
                candidate.matchedFacts(),
                candidate.rationale());
    }
}
