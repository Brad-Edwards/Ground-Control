package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.Map;
import java.util.UUID;

public record EvidenceCollectionRequest(
        UUID projectId,
        EvidenceConnectionConfig connection,
        EvidenceCollectionScope scope,
        EvidenceCollectionRateLimit rateLimitOverride,
        Map<String, Object> options) {

    /**
     * {@code options} key carrying the requested output {@code schemaId} so an adapter can honor a
     * caller's configured evidence schema. The port has no typed schema-selection field; {@code options}
     * is its extension bag, and this is its canonical key (mirrors the per-family {@code schemaId}
     * carried on {@link EvidenceCollectionOutputSchema}).
     */
    public static final String SCHEMA_OPTION = "schemaId";

    public EvidenceCollectionRequest {
        if (projectId == null) {
            throw new DomainValidationException("Evidence collection projectId must not be null");
        }
        if (connection == null) {
            throw new DomainValidationException("Evidence collection connection must not be null");
        }
        if (scope == null) {
            throw new DomainValidationException("Evidence collection scope must not be null");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
