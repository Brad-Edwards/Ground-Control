package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import java.time.Instant;
import java.util.UUID;

/** GC-RSCH-F006 — read view of a {@link ResearchRunMethodologySource} row. */
public record ResearchRunMethodologySourceResponse(
        UUID id,
        UUID selectionId,
        String sourceRef,
        String sourceLabel,
        boolean required,
        MethodologySourceState state,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunMethodologySourceResponse from(ResearchRunMethodologySource s) {
        return new ResearchRunMethodologySourceResponse(
                s.getId(),
                s.getSelection().getId(),
                s.getSourceRef(),
                s.getSourceLabel(),
                s.isRequired(),
                s.getState(),
                s.getActor(),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
