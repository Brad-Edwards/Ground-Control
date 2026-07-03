package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;

/**
 * Record (or rework) a stage's output artifact in the run manifest. The
 * artifact type must match the run's current stage. {@code idempotencyKey},
 * when supplied, makes the write retry-safe (GC-RSCH-F036): a repeat with the
 * same key returns the existing record instead of creating a duplicate. The
 * optional bounded source counts update the run's observability summary
 * (ADR-065 §5); a null count leaves the corresponding summary unchanged. The
 * optional {@code dataClass} records the artifact's privacy/access
 * classification (GC-RSCH-N006 / ADR-084 §2). The recording actor is taken from
 * the authenticated server context (ADR-026), not this command, so lifecycle
 * provenance cannot be forged by the caller.
 */
public record RecordArtifactCommand(
        ResearchArtifactType artifactType,
        String locator,
        String contentHash,
        String idempotencyKey,
        Integer candidateSources,
        Integer screenedIncluded,
        Integer screenedExcluded,
        Integer chartedFullText,
        Integer accessGaps,
        ResearchDataClass dataClass) {

    /** Convenience constructor for callers that do not classify the artifact (dataClass = null). */
    public RecordArtifactCommand(
            ResearchArtifactType artifactType,
            String locator,
            String contentHash,
            String idempotencyKey,
            Integer candidateSources,
            Integer screenedIncluded,
            Integer screenedExcluded,
            Integer chartedFullText,
            Integer accessGaps) {
        this(
                artifactType,
                locator,
                contentHash,
                idempotencyKey,
                candidateSources,
                screenedIncluded,
                screenedExcluded,
                chartedFullText,
                accessGaps,
                null);
    }
}
