package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.controlidentification.service.ThreatControlCoverage;
import java.util.List;
import java.util.UUID;

/**
 * API response for "which controls cover threat X" (GC-GRC-008 acceptance). Lists the controls recorded
 * as covering the threat and which canonical mapping edges record each.
 */
public record ControlCoverageResponse(
        String projectIdentifier, UUID threatModelId, int controlCount, List<CoveredControlResponse> controls) {

    public static ControlCoverageResponse from(String projectIdentifier, ThreatControlCoverage coverage) {
        var controls =
                coverage.controls().stream().map(CoveredControlResponse::from).toList();
        return new ControlCoverageResponse(projectIdentifier, coverage.threatModelId(), controls.size(), controls);
    }

    /** API response DTO for a single covering control. */
    public record CoveredControlResponse(
            UUID controlId,
            String controlUid,
            String title,
            boolean viaRiskControlMapping,
            boolean viaThreatModelLink) {

        public static CoveredControlResponse from(
                com.keplerops.groundcontrol.domain.controlidentification.service.CoveredControl covered) {
            return new CoveredControlResponse(
                    covered.controlId(),
                    covered.controlUid(),
                    covered.title(),
                    covered.viaRiskControlMapping(),
                    covered.viaThreatModelLink());
        }
    }
}
