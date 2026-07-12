package com.keplerops.groundcontrol.domain.requirements.state;

import java.util.Set;

/**
 * Lifecycle status for a requirement. Transitions are governed by a
 * switch-based state machine.
 *
 * <pre>
 * DRAFT ──► ACTIVE ──► DEPRECATED ──► ARCHIVED
 *   │              └──────────────────►
 *   └──► DEPRECATED
 * </pre>
 */
@SuppressWarnings("java:S125") // JML contract annotations are intentional, not dead code
public enum Status {
    DRAFT,
    ACTIVE,
    DEPRECATED,
    ARCHIVED;

    /*@ ensures \result != null; @*/
    public /*@ pure @*/ Set<Status> validTargets() {
        return switch (this) {
                // DRAFT -> DEPRECATED withdraws a requirement that was never implemented. Without it
                // DRAFT has no terminal state, so the only way to retire an unbuilt requirement is to
                // promote it through ACTIVE first — stamping a false "this shipped" event on something
                // that never did. Withdrawal is not the same claim as deprecation-after-delivery, and
                // the audit history has to be able to tell them apart.
            case DRAFT -> Set.of(ACTIVE, DEPRECATED);
            case ACTIVE -> Set.of(DEPRECATED, ARCHIVED);
            case DEPRECATED -> Set.of(ARCHIVED);
            case ARCHIVED -> Set.of();
        };
    }

    /*@ requires target != null; @*/
    public /*@ pure @*/ boolean canTransitionTo(Status target) {
        return validTargets().contains(target);
    }
}
