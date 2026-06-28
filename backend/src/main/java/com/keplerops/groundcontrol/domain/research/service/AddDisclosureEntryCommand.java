package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import java.util.UUID;

/**
 * Add one entry to a run's disclosure (GC-RSCH-N013, ADR-068 §4). {@code
 * uncertaintyCategory} is required iff {@code family} is {@code
 * UNRESOLVED_UNCERTAINTY}. {@code summary}, {@code sectionKey}, {@code locator},
 * and {@code modelLabel} are bounded. The actor is taken from the authenticated
 * server context (ADR-026), not this command.
 */
public record AddDisclosureEntryCommand(
        DisclosureEntryFamily family,
        DisclosureUncertaintyCategory uncertaintyCategory,
        String sectionKey,
        String locator,
        String modelLabel,
        String summary,
        UUID rationaleEntryId,
        UUID decisionLogId,
        UUID reviewCommentId) {}
