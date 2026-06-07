package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Duration;
import java.time.Instant;

public record EvidenceCollectionRateLimit(int capacity, Duration window, int remaining, Instant resetAt) {

    public EvidenceCollectionRateLimit {
        if (capacity < 1) {
            throw new DomainValidationException("Evidence collection rate-limit capacity must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new DomainValidationException("Evidence collection rate-limit window must be positive");
        }
        if (remaining < 0 || remaining > capacity) {
            throw new DomainValidationException("Evidence collection rate-limit remaining must be within capacity");
        }
    }
}
