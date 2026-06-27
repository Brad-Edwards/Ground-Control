package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R003 / ADR-063 — the recorded outcome of a resolved {@link
 * ResearchGatePoint}. {@code APPROVED} / {@code REJECTED} are human decisions;
 * {@code AUTO_ACCEPTED} is recorded when an {@code AUTONOMOUS_DEFAULT} gate uses
 * the declared default without a human click. A {@code REJECTED} gate blocks the
 * guarded stage exit until the stage artifact is reworked.
 */
public enum ResearchGateDecisionOutcome {
    APPROVED,
    REJECTED,
    AUTO_ACCEPTED;

    /** Whether this outcome permits the guarded stage exit to proceed. */
    public boolean permitsAdvance() {
        return this == APPROVED || this == AUTO_ACCEPTED;
    }
}
