package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link ResearchRunDisclosure} (GC-RSCH-N013, ADR-068 §4).
 * DTOs (not the controller) name the domain enums (ArchUnit boundary).
 */
public record ResearchRunDisclosureResponse(
        UUID id,
        UUID finalArtifactId,
        Integer finalAttemptNo,
        DisclosureStatus status,
        boolean aiPartsDeclaredNone,
        boolean uncertaintyDeclaredNone,
        boolean humanApprovalsDeclaredNone,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunDisclosureResponse from(ResearchRunDisclosure d) {
        return new ResearchRunDisclosureResponse(
                d.getId(),
                d.getFinalArtifactId(),
                d.getFinalAttemptNo(),
                d.getStatus(),
                d.isAiPartsDeclaredNone(),
                d.isUncertaintyDeclaredNone(),
                d.isHumanApprovalsDeclaredNone(),
                d.getActor(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }
}
