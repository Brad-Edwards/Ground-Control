package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N013 / ADR-068 — the category of an unresolved uncertainty disclosed
 * on a {@link ResearchRunDisclosureEntry} whose family is {@code
 * UNRESOLVED_UNCERTAINTY}.
 */
public enum DisclosureUncertaintyCategory {
    SCIENTIFIC,
    ACCESS_GAP,
    WORKFLOW_ERROR,
    UNRESOLVED_REVIEW
}
