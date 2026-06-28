package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import com.keplerops.groundcontrol.domain.research.service.AddDisclosureEntryCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Add one entry to a run's disclosure (GC-RSCH-N013, ADR-068 §4). The actor
 * is taken from the authenticated server context, not the request body
 * (ADR-026). {@code uncertaintyCategory} is required iff {@code family} is
 * {@code UNRESOLVED_UNCERTAINTY}.
 */
public record AddDisclosureEntryRequest(
        @NotNull DisclosureEntryFamily family,
        DisclosureUncertaintyCategory uncertaintyCategory,
        @Size(max = 200) String sectionKey,
        @Size(max = 500) String locator,
        @Size(max = 200) String modelLabel,
        @NotNull @Size(max = 2000) String summary,
        UUID rationaleEntryId,
        UUID decisionLogId,
        UUID reviewCommentId) {

    public AddDisclosureEntryCommand toCommand() {
        return new AddDisclosureEntryCommand(
                family,
                uncertaintyCategory,
                sectionKey,
                locator,
                modelLabel,
                summary,
                rationaleEntryId,
                decisionLogId,
                reviewCommentId);
    }
}
