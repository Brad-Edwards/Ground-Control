package com.keplerops.groundcontrol.domain.research.model;

import java.util.Set;

/**
 * GC-RSCH-F003 / GC-RSCH-N007 / ADR-063 — run-level lifecycle status, kept
 * separate from {@link ResearchRunStage} (stage answers "where in the
 * lifecycle"; status answers "what is happening to the run").
 *
 * <p>A run starts {@code IN_PROGRESS}. A required human gate rejection moves it
 * to {@code BLOCKED}; the operator may stop it ({@code STOPPED}) or it may fail
 * ({@code FAILED}). Both {@code STOPPED} and {@code FAILED} are resumable back
 * to {@code IN_PROGRESS} (GC-RSCH-F036). {@code COMPLETED} is terminal.
 */
public enum ResearchRunStatus {
    IN_PROGRESS,
    BLOCKED,
    STOPPED,
    FAILED,
    COMPLETED;

    public Set<ResearchRunStatus> validTargets() {
        return switch (this) {
            case IN_PROGRESS -> Set.of(BLOCKED, STOPPED, FAILED, COMPLETED);
            case BLOCKED -> Set.of(IN_PROGRESS, STOPPED, FAILED);
            case STOPPED -> Set.of(IN_PROGRESS);
            case FAILED -> Set.of(IN_PROGRESS);
            case COMPLETED -> Set.of();
        };
    }

    public boolean canTransitionTo(ResearchRunStatus target) {
        return target != null && validTargets().contains(target);
    }

    /** A run that has stopped or failed but can be resumed (GC-RSCH-F036). */
    public boolean isResumable() {
        return this == STOPPED || this == FAILED;
    }
}
