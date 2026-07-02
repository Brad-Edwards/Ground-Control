package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import java.time.Instant;
import java.util.UUID;

/** GC-RSCH-F006 — read view of a {@link ResearchRunMethodologySelection} row. */
public record ResearchRunMethodologySelectionResponse(
        UUID id,
        String methodKey,
        String methodLabel,
        String profileVersion,
        String catalogVersion,
        String actor,
        Instant supersededAt,
        Instant createdAt) {

    public static ResearchRunMethodologySelectionResponse from(ResearchRunMethodologySelection s) {
        return new ResearchRunMethodologySelectionResponse(
                s.getId(),
                s.getMethodKey(),
                s.getMethodLabel(),
                s.getProfileVersion(),
                s.getCatalogVersion(),
                s.getActor(),
                s.getSupersededAt(),
                s.getCreatedAt());
    }
}
