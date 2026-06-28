package com.keplerops.groundcontrol.domain.evidence.collection.cmdb;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.Locale;

/**
 * Canonical CMDB and asset-management evidence provider keys for the GC-S004 adapter
 * specification.
 *
 * <p>These are the configuration-management-database and asset-management platforms a CMDB
 * evidence collection adapter may target. The {@link #key()} is the stable, provider-neutral
 * discriminator a connection profile carries and that an adapter advertises as a
 * {@code provider:<key>} descriptor capability. Provider credentials are never modeled here;
 * they stay external as a {@code credentialRef} secret reference on the collection request.
 */
public enum CmdbEvidenceProvider {
    SERVICENOW("servicenow"),
    SNIPE_IT("snipe-it"),
    JAMF("jamf");

    private final String key;

    CmdbEvidenceProvider(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Descriptor capability token advertised by an adapter that supports this provider. */
    public String capabilityToken() {
        return "provider:" + key;
    }

    /**
     * Resolves a provider by its stable key, case-insensitively.
     *
     * @throws DomainValidationException when the key names no supported provider, so an
     *     unsupported provider is surfaced rather than silently ignored.
     */
    public static CmdbEvidenceProvider fromKey(String key) {
        if (key != null) {
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            for (CmdbEvidenceProvider provider : values()) {
                if (provider.key.equals(normalized)) {
                    return provider;
                }
            }
        }
        throw new DomainValidationException("Unsupported CMDB evidence provider: " + key);
    }
}
