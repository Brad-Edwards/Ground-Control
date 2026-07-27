package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Instant;
import java.util.Map;

final class EffectiveWindow {

    private EffectiveWindow() {}

    static void validate(Instant effectiveFrom, Instant effectiveUntil) {
        if (effectiveFrom != null && effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new DomainValidationException(
                    "effectiveUntil must be after effectiveFrom",
                    "invalid_effective_window",
                    Map.of("field", "effectiveUntil"));
        }
    }
}
