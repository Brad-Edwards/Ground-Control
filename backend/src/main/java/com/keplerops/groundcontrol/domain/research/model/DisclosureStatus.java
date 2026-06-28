package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N013 / ADR-068 — freshness of a {@link ResearchRunDisclosure}. A
 * disclosure is {@code CURRENT} until its tied manuscript is superseded, which
 * marks it {@code STALE}.
 */
public enum DisclosureStatus {
    CURRENT,
    STALE
}
