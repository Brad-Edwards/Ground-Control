package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.net.URI;
import java.util.Map;

public record EvidenceConnectionConfig(
        String profileId, URI endpoint, String credentialRef, Map<String, Object> settings) {

    /**
     * {@code settings} key carrying the {@code List<String>} of pre-validated literal IP addresses
     * the {@code endpoint} host resolved to at request-build time (GC-S005 SSRF defense). When
     * present, an adapter making the outbound call MUST connect to one of these pinned addresses
     * rather than re-resolving the hostname - re-resolution reopens the DNS-rebinding window the
     * pin exists to close. The original {@code endpoint} host is still used for TLS SNI / Host
     * header. Absent for non-scheduled callers that do not pre-resolve.
     */
    public static final String PINNED_ADDRESSES_SETTING = "pinnedAddresses";

    public EvidenceConnectionConfig {
        if (profileId == null || profileId.isBlank()) {
            throw new DomainValidationException("Evidence collection profileId must not be blank");
        }
        if (endpoint == null) {
            throw new DomainValidationException("Evidence collection endpoint must not be null");
        }
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new DomainValidationException("Evidence collection credentialRef must not be blank");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
