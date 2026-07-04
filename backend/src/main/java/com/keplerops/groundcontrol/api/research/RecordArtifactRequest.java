package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Record (or rework) the current stage's output artifact. Bounded metadata only;
 * the optional source counts feed the observability summary and the optional
 * {@code dataClass} records the artifact's privacy/access classification
 * (GC-RSCH-N006). The recording actor is taken from the authenticated server
 * context, not the request body (ADR-026).
 */
public record RecordArtifactRequest(
        @NotNull ResearchArtifactType artifactType,
        @Size(max = 500) String locator,
        @Size(max = 128) String contentHash,
        @Size(max = 200) String idempotencyKey,
        @PositiveOrZero Integer candidateSources,
        @PositiveOrZero Integer screenedIncluded,
        @PositiveOrZero Integer screenedExcluded,
        @PositiveOrZero Integer chartedFullText,
        @PositiveOrZero Integer accessGaps,
        ResearchDataClass dataClass) {

    public RecordArtifactCommand toCommand() {
        return new RecordArtifactCommand(
                artifactType,
                locator,
                contentHash,
                idempotencyKey,
                candidateSources,
                screenedIncluded,
                screenedExcluded,
                chartedFullText,
                accessGaps,
                dataClass);
    }
}
