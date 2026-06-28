package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N012 / ADR-068 — origin of a {@link ResearchRunRationaleEntry}.
 * Distinguishes a human-authored rationale from an agent recommendation, an
 * autonomous default, an imported artifact, or an adapter-supplied entry.
 */
public enum RationaleProvenance {
    HUMAN,
    AGENT_RECOMMENDATION,
    AUTONOMOUS_DEFAULT,
    IMPORTED_ARTIFACT,
    ADAPTER
}
