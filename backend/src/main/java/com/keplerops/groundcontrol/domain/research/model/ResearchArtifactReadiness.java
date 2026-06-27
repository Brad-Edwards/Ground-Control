package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N011 / ADR-065 §4 — computed readiness of a stage's required artifact,
 * derived from manifest state (not file content). {@code READY} means an active,
 * non-superseded record satisfies the prerequisite; {@code MISSING} means no
 * record exists; {@code FAILED} / {@code SUPERSEDED} report the most recent
 * non-active record so a stalled run is not mistaken for an unstarted one.
 */
public enum ResearchArtifactReadiness {
    READY,
    MISSING,
    FAILED,
    SUPERSEDED
}
