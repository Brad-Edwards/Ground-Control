package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R004 / GC-RSCH-N002 / ADR-069 §2 — closed set of provenance node kinds.
 * Together the kinds cover the full R004 derivation chain from the user goal to
 * final prose: a node identifies one bounded research referent at a point in the
 * chain. Adding a kind follows the ADR-034 mirror/drift rules (this enum is
 * API-visible and mirrored in the MCP layer and the OpenAPI contract).
 *
 * <p>The ledger stores bounded references and summaries only; a node never holds
 * raw artifact content (queries, full text, charting rows, manuscript prose,
 * prompts, provider payloads, or secrets).
 */
public enum ProvenanceNodeKind {
    /** The user research goal or the intake snapshot that anchors the chain. */
    USER_GOAL,
    /** A methodology source selected to drive the protocol. */
    METHODOLOGY_SOURCE,
    /** A search protocol step or executed query. */
    QUERY,
    /** A candidate source / source record surfaced by search. */
    CANDIDATE_SOURCE,
    /** A full-text access state (retrieved, paywalled, access-gap) for a source. */
    FULL_TEXT_ACCESS,
    /** A single charting-form cell with field-level provenance (GC-RSCH-F019). */
    CHARTING_CELL,
    /** A single evidence-matrix cell linking a source to a charted field/code (GC-RSCH-F024). */
    EVIDENCE_MATRIX_CELL,
    /** A synthesis claim derived from charted evidence. */
    SYNTHESIS_CLAIM,
    /** An argument move in the argument map. */
    ARGUMENT_MOVE,
    /** A final-prose locator (section/paragraph) in the manuscript. */
    FINAL_PROSE
}
