package com.keplerops.groundcontrol.domain.derivation.service;

import java.util.List;

public record DerivationRoutePlan(List<DerivationAdapter> adapters, List<DerivationCaptureLimitDraft> captureLimits) {

    public DerivationRoutePlan {
        adapters = adapters == null ? List.of() : List.copyOf(adapters);
        captureLimits = captureLimits == null ? List.of() : List.copyOf(captureLimits);
    }
}
