package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.util.Map;

public record DerivedSystemModelFact(
        SystemModelFactKind factKind,
        String factKey,
        String label,
        String summary,
        String sourcePath,
        Map<String, Object> payload,
        DerivationFactProvenance provenance) {

    public DerivedSystemModelFact {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
