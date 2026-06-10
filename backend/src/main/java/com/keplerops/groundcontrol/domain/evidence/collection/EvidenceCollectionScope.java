package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Instant;
import java.util.Map;

public record EvidenceCollectionScope(
        String scopeType, Map<String, Object> criteria, Instant from, Instant to, Integer itemLimit) {

    public EvidenceCollectionScope {
        if (scopeType == null || scopeType.isBlank()) {
            throw new DomainValidationException("Evidence collection scopeType must not be blank");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainValidationException("Evidence collection scope from must be before to");
        }
        if (itemLimit != null && itemLimit < 1) {
            throw new DomainValidationException("Evidence collection itemLimit must be positive");
        }
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }
}
