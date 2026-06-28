package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link ResearchRunDisclosureEntry} (GC-RSCH-N013, ADR-068 §4).
 * DTOs (not the controller) name the domain enums (ArchUnit boundary).
 */
public record ResearchRunDisclosureEntryResponse(
        UUID id,
        UUID disclosureId,
        DisclosureEntryFamily family,
        DisclosureUncertaintyCategory uncertaintyCategory,
        String sectionKey,
        String locator,
        String modelLabel,
        String summary,
        UUID rationaleEntryId,
        UUID decisionLogId,
        UUID reviewCommentId,
        String actor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunDisclosureEntryResponse from(ResearchRunDisclosureEntry e) {
        return new ResearchRunDisclosureEntryResponse(
                e.getId(),
                e.getDisclosure().getId(),
                e.getFamily(),
                e.getUncertaintyCategory(),
                e.getSectionKey(),
                e.getLocator(),
                e.getModelLabel(),
                e.getSummary(),
                e.getRationaleEntryId(),
                e.getDecisionLogId(),
                e.getReviewCommentId(),
                e.getActor(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
