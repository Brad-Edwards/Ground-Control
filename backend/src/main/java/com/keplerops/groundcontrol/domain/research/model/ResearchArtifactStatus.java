package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F003 / GC-RSCH-F036 / ADR-063 — durable status of a research-run
 * artifact manifest record. Exactly one {@code ACTIVE} record of a given type
 * may exist per run; rework {@code SUPERSEDED}s the prior record rather than
 * mutating it in place, and a recorded failure marks the attempt {@code FAILED}.
 */
public enum ResearchArtifactStatus {
    ACTIVE,
    SUPERSEDED,
    FAILED
}
