package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N012 / ADR-068 — the evidentiary basis a {@link
 * ResearchRunRationaleEntry} rests on, identifying what kind of source backs the
 * recorded decision.
 */
public enum RationaleEvidenceBasis {
    METHODOLOGY_SOURCE,
    USER_DECISION,
    CITED_SOURCE,
    FULL_TEXT_SPAN,
    CHARTED_CELL,
    EVIDENCE_MATRIX_CELL,
    ARGUMENT_MAP_PREMISE,
    MANUSCRIPT_CITATION,
    POLICY_DEFAULT,
    EXPLICIT_LIMITATION
}
