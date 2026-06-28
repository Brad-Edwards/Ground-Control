package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N013 / ADR-068 — the disclosure family a {@link
 * ResearchRunDisclosureEntry} belongs to: an AI-generated portion of the
 * manuscript, or an unresolved uncertainty that must be surfaced.
 */
public enum DisclosureEntryFamily {
    AI_GENERATED_PART,
    UNRESOLVED_UNCERTAINTY
}
