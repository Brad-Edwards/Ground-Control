package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N004 / ADR-069 §4 — lifecycle status for an append-only provenance
 * record (node or edge). Provenance is historical product state: a record is
 * never edited in place to describe a replacement. Rework marks the prior record
 * {@code SUPERSEDED} and inserts a new {@code ACTIVE} record, so current
 * provenance for the active artifact attempt stays distinguishable from
 * historical provenance for superseded attempts.
 */
public enum ProvenanceRecordStatus {
    ACTIVE,
    SUPERSEDED
}
