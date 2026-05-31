package com.keplerops.groundcontrol.domain.evidence.service;

import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Create / supersede command for {@link
 * com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact}.
 *
 * <p>{@code expiresAt} and {@code validityWindowDays} are GC-I004 fields. Both
 * are optional and append-only: setting them does not mutate existing rows;
 * the sweep job derives current-state expiry events from {@code expiresAt}
 * without overwriting any fields.
 */
public record CreateEvidenceArtifactCommand(
        UUID projectId,
        String uid,
        String title,
        String summary,
        EvidenceType evidenceType,
        String derivationMethod,
        Instant derivedAt,
        AssuranceLevel assuranceLevel,
        String confidence,
        String notes,
        List<EvidenceSourceRef> sources,
        Instant expiresAt,
        Integer validityWindowDays) {

    /**
     * Back-compat constructor for callers (mostly tests) that predate the
     * GC-I004 expiration fields. Both new fields default to {@code null}.
     */
    public CreateEvidenceArtifactCommand(
            UUID projectId,
            String uid,
            String title,
            String summary,
            EvidenceType evidenceType,
            String derivationMethod,
            Instant derivedAt,
            AssuranceLevel assuranceLevel,
            String confidence,
            String notes,
            List<EvidenceSourceRef> sources) {
        this(
                projectId,
                uid,
                title,
                summary,
                evidenceType,
                derivationMethod,
                derivedAt,
                assuranceLevel,
                confidence,
                notes,
                sources,
                null,
                null);
    }
}
