package com.keplerops.groundcontrol.domain.evidence.collection.iam;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.Locale;

/**
 * Canonical IAM evidence provider keys for the GC-S002 adapter specification.
 *
 * <p>These are the identity systems an IAM evidence collection adapter may target. The
 * {@link #key()} is the stable, provider-neutral discriminator a connection profile
 * carries and that an adapter advertises as a {@code provider:<key>} descriptor
 * capability. Provider credentials are never modeled here; they stay external as a
 * {@code credentialRef} secret reference on the collection request.
 */
public enum IamEvidenceProvider {
    OKTA("okta"),
    AZURE_AD("azure-ad"),
    AWS_IAM("aws-iam");

    private final String key;

    IamEvidenceProvider(String key) {
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
    public static IamEvidenceProvider fromKey(String key) {
        if (key != null) {
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            for (IamEvidenceProvider provider : values()) {
                if (provider.key.equals(normalized)) {
                    return provider;
                }
            }
        }
        throw new DomainValidationException("Unsupported IAM evidence provider: " + key);
    }
}
