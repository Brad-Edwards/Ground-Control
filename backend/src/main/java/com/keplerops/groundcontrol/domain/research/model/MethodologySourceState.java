package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F006 — lifecycle state of a single methodology source within an
 * active {@link ResearchRunMethodologySelection}. Transitions follow a closed
 * directed graph:
 * <ul>
 *   <li>ATTEMPTED → {OBTAINED, BLOCKED}</li>
 *   <li>OBTAINED → {READ, BLOCKED}</li>
 *   <li>BLOCKED → {ATTEMPTED} (re-attempt after unblock)</li>
 *   <li>READ → {} (terminal)</li>
 * </ul>
 * Optional sources carry any state; only {@code required} sources must reach
 * {@code READ} before the {@code METHODOLOGY_REQUIREMENTS} artifact gate will open.
 */
public enum MethodologySourceState {

    /** The source has been identified; retrieval has not succeeded yet. */
    ATTEMPTED,

    /** The source has been retrieved but not yet fully read. */
    OBTAINED,

    /** The source has been read in full and understood by the researcher. */
    READ,

    /**
     * Retrieval or reading is blocked (paywall, access restriction, etc.).
     * A required source in this state triggers a {@link
     * com.keplerops.groundcontrol.domain.exception.ConflictException} at the
     * coverage gate rather than the normal validation error.
     */
    BLOCKED;

    /**
     * Returns whether a transition from {@code this} state to {@code target} is
     * permitted under the closed transition graph. Transitions to the same state
     * are always permitted (idempotent re-submission).
     */
    public boolean canTransitionTo(MethodologySourceState target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case ATTEMPTED -> target == OBTAINED || target == BLOCKED;
            case OBTAINED -> target == READ || target == BLOCKED;
            case BLOCKED -> target == ATTEMPTED;
            case READ -> false; // terminal state
        };
    }
}
