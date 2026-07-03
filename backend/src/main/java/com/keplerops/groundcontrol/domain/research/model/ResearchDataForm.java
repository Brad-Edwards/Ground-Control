package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N006 / ADR-085 §2–3 — the form in which research material may leave
 * the local boundary. Declaration order is a total order of increasing
 * disclosure: authorizing derived metadata, identifiers/hashes, or short
 * summaries is strictly distinct from authorizing raw content (manuscript prose,
 * PDF text, reviewer notes, credentials). An egress request is permitted only
 * when the policy allows a form at least as disclosing as the one requested.
 */
public enum ResearchDataForm {
    NONE,
    DERIVED_METADATA,
    SUMMARY,
    RAW_CONTENT;

    /** True when this form is at least as disclosing as {@code requested} (ordinal order). */
    public boolean permits(ResearchDataForm requested) {
        return requested != null && this.ordinal() >= requested.ordinal();
    }
}
