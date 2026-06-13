package com.keplerops.groundcontrol.domain.derivation.service;

/**
 * Port for static-analysis derivation adapters.
 *
 * <p>Adapters receive a repository scope and return normalized system-model
 * facts plus any adapter-local capture limits. They do not persist results;
 * persistence is owned by {@link DerivationService}.
 */
public interface DerivationAdapter {

    DerivationAdapterDescriptor descriptor();

    boolean isAvailable();

    DerivationAdapterResult derive(DerivationAdapterRequest request);
}
