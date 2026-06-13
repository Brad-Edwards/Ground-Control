package com.keplerops.groundcontrol.api.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationRunResult;
import java.util.List;

public record DerivationRunResultResponse(
        DerivationRunResponse run,
        List<SystemModelFactResponse> facts,
        List<DerivationCaptureLimitResponse> captureLimits) {

    public static DerivationRunResultResponse from(DerivationRunResult result) {
        return new DerivationRunResultResponse(
                DerivationRunResponse.from(result.run()),
                result.facts().stream().map(SystemModelFactResponse::from).toList(),
                result.captureLimits().stream()
                        .map(DerivationCaptureLimitResponse::from)
                        .toList());
    }
}
