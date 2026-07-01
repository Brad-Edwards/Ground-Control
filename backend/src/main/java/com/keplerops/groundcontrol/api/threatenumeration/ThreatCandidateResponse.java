package com.keplerops.groundcontrol.api.threatenumeration;

import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidate;
import java.util.Map;

/** API response DTO for a single enumerated threat candidate (GC-GRC-007). */
public record ThreatCandidateResponse(
        String producingRuleId,
        String category,
        String strideCategory,
        String elementStableKey,
        String elementKind,
        Map<String, String> matchedFacts,
        String narrative) {

    public static ThreatCandidateResponse from(ThreatCandidate candidate) {
        return new ThreatCandidateResponse(
                candidate.producingRuleId(),
                candidate.category() != null ? candidate.category().name() : null,
                candidate.strideCategory() != null ? candidate.strideCategory().name() : null,
                candidate.elementStableKey(),
                candidate.elementKind() != null ? candidate.elementKind().name() : null,
                candidate.matchedFacts(),
                candidate.narrative());
    }
}
