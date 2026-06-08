package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.Map;

public record EvidenceCollectionOutputSchema(
        String schemaId, String schemaVersion, EvidenceType evidenceType, Map<String, Object> payloadShape) {

    public EvidenceCollectionOutputSchema {
        if (schemaId == null || schemaId.isBlank()) {
            throw new DomainValidationException("Evidence collection schemaId must not be blank");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new DomainValidationException("Evidence collection schemaVersion must not be blank");
        }
        if (evidenceType == null) {
            throw new DomainValidationException("Evidence collection evidenceType must not be null");
        }
        payloadShape = payloadShape == null ? Map.of() : Map.copyOf(payloadShape);
    }
}
