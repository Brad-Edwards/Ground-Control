package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Instant;
import java.util.List;

public record EvidenceCollectionResult(
        String adapterName,
        String adapterVersion,
        EvidenceCollectionStatus status,
        EvidenceCollectionOutputSchema schema,
        Instant collectedAt,
        List<CreateEvidenceArtifactCommand> artifacts,
        List<String> externalReferences,
        List<EvidenceCollectionError> errors,
        EvidenceCollectionRateLimit rateLimit) {

    public EvidenceCollectionResult {
        if (adapterName == null || adapterName.isBlank()) {
            throw new DomainValidationException("Evidence collection adapterName must not be blank");
        }
        if (adapterVersion == null || adapterVersion.isBlank()) {
            throw new DomainValidationException("Evidence collection adapterVersion must not be blank");
        }
        if (status == null) {
            throw new DomainValidationException("Evidence collection status must not be null");
        }
        if (schema == null) {
            throw new DomainValidationException("Evidence collection schema must not be null");
        }
        if (collectedAt == null) {
            throw new DomainValidationException("Evidence collection collectedAt must not be null");
        }
        if (rateLimit == null) {
            throw new DomainValidationException("Evidence collection rateLimit must not be null");
        }
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        externalReferences = externalReferences == null ? List.of() : List.copyOf(externalReferences);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
