package com.keplerops.groundcontrol.domain.backlog.state;

import java.util.EnumSet;
import java.util.Set;

/**
 * Backlog item lifecycle per GC-W003. CANDIDATE → READY when CoD components
 * are calibrated; READY → IN_PROGRESS / DONE / ARCHIVED follow standard flow.
 */
public enum BacklogItemStatus {
    CANDIDATE,
    READY,
    IN_PROGRESS,
    DONE,
    ARCHIVED;

    public Set<BacklogItemStatus> validTargets() {
        return switch (this) {
            case CANDIDATE -> EnumSet.of(READY, ARCHIVED);
            case READY -> EnumSet.of(IN_PROGRESS, CANDIDATE, ARCHIVED);
            case IN_PROGRESS -> EnumSet.of(DONE, READY, ARCHIVED);
            case DONE -> EnumSet.of(ARCHIVED);
            case ARCHIVED -> EnumSet.noneOf(BacklogItemStatus.class);
        };
    }

    public boolean canTransitionTo(BacklogItemStatus target) {
        return validTargets().contains(target);
    }
}
