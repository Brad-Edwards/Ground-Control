package com.keplerops.groundcontrol.domain.derivation.service;

import java.util.List;

public record DerivationAdapterResult(
        List<DerivedSystemModelFact> facts, List<DerivationCaptureLimitDraft> captureLimits) {

    public DerivationAdapterResult {
        facts = facts == null ? List.of() : List.copyOf(facts);
        captureLimits = captureLimits == null ? List.of() : List.copyOf(captureLimits);
    }

    public static DerivationAdapterResult facts(List<DerivedSystemModelFact> facts) {
        return new DerivationAdapterResult(facts, List.of());
    }
}
