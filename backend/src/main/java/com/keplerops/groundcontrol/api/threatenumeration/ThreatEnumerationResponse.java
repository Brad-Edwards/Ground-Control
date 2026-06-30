package com.keplerops.groundcontrol.api.threatenumeration;

import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationLimitation;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationResult;
import java.util.List;

/**
 * API response for a deterministic STRIDE threat enumeration run (GC-GRC-007). Candidates are
 * ordered by {@code (elementStableKey, producingRuleId, strideCategory)} for byte-stable
 * serialization across repeated calls with the same inputs.
 */
public record ThreatEnumerationResponse(
        String projectIdentifier,
        String schemaVersion,
        String packId,
        String resolvedVersion,
        String checksum,
        String snapshotId,
        String modelVersion,
        int candidateCount,
        int limitationCount,
        List<ThreatCandidateResponse> candidates,
        List<LimitationResponse> limitations) {

    public static ThreatEnumerationResponse from(String projectIdentifier, ThreatEnumerationResult result) {
        var candidates =
                result.candidates().stream().map(ThreatCandidateResponse::from).toList();
        var limitations =
                result.limitations().stream().map(LimitationResponse::from).toList();
        return new ThreatEnumerationResponse(
                projectIdentifier,
                result.schemaVersion(),
                result.packId(),
                result.resolvedVersion(),
                result.checksum(),
                result.snapshotId(),
                result.modelVersion(),
                candidates.size(),
                limitations.size(),
                candidates,
                limitations);
    }

    /** API response DTO for a single enumeration limitation. */
    public record LimitationResponse(String reason, String detail, String elementStableKey) {

        public static LimitationResponse from(ThreatEnumerationLimitation limitation) {
            return new LimitationResponse(
                    limitation.reason() != null ? limitation.reason().name() : null,
                    limitation.detail(),
                    limitation.elementStableKey());
        }
    }
}
