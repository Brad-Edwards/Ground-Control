package com.keplerops.groundcontrol.domain.research.model;

/**
 * Intended output shape of the research run. Non-OTHER values mirror the
 * method keys in {@code skills/lit-review/methodology/catalog.yaml} so the
 * phase-1 lit-review skill can derive a methodology choice from the intake.
 * See ADR-056.
 */
public enum IntendedOutput {
    SCOPING_REVIEW,
    SYSTEMATIC_REVIEW,
    SYSTEMATIC_MAP,
    CRITICAL_REVIEW,
    NARRATIVE_REVIEW,
    TARGETED_RELATED_WORK,
    TAXONOMY_PAPER,
    OTHER
}
