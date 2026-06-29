package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R004 / GC-RSCH-N002 / ADR-069 §2 — closed set of provenance edge
 * relations. An edge runs from an upstream input node to a downstream output
 * node, so a downstream node (a synthesis claim or final-prose locator) can be
 * traversed backward to the user goal and the source evidence that supports it.
 * API-visible; adding a relation follows the ADR-034 mirror/drift rules.
 */
public enum ProvenanceEdgeRelation {
    /** Downstream node was derived from the upstream node. */
    DERIVED_FROM,
    /** Upstream evidence supports the downstream claim. */
    SUPPORTS,
    /** Upstream candidate/source was selected (screened in) for the downstream step. */
    SELECTED,
    /** Downstream prose/claim cites the upstream source. */
    CITED,
    /** Upstream node contributed to the downstream node (general contribution). */
    CONTRIBUTED_TO
}
