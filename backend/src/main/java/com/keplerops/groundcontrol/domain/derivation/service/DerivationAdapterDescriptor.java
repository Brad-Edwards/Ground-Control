package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record DerivationAdapterDescriptor(
        String adapterId,
        String toolName,
        String toolVersion,
        String rulesetName,
        String rulesetVersion,
        Set<String> languages,
        Set<String> surfaces,
        Set<DerivationScopeMode> scopeModes,
        Set<SystemModelFactKind> factKinds) {

    public DerivationAdapterDescriptor {
        languages = normalize(languages);
        surfaces = normalize(surfaces);
        scopeModes = scopeModes == null ? Set.of() : Set.copyOf(scopeModes);
        factKinds = factKinds == null ? Set.of() : Set.copyOf(factKinds);
    }

    public boolean supportsLanguage(String language) {
        return languages.contains(normalizeOne(language));
    }

    public boolean supportsSurface(String surface) {
        return surfaces.contains(normalizeOne(surface));
    }

    public boolean supportsScopeMode(DerivationScopeMode mode) {
        return scopeModes.contains(mode);
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().map(DerivationAdapterDescriptor::normalizeOne).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeOne(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
