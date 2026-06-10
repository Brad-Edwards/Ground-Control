package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.net.URI;
import java.util.Map;

public record EvidenceConnectionConfig(
        String profileId, URI endpoint, String credentialRef, Map<String, Object> settings) {

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
