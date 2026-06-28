package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N012 / ADR-068 — the kind of decision a {@link
 * ResearchRunRationaleEntry} records in the run's rationale ledger.
 */
public enum RationaleEntryKind {
    METHODOLOGY_CHOICE,
    SEARCH_DECISION,
    EXCLUSION,
    CHARTED_VALUE,
    SYNTHESIS_CLAIM,
    WRITING_CLAIM
}
