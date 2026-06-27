package com.keplerops.groundcontrol.domain.research.model;

import java.util.Optional;

/**
 * GC-RSCH-R003 / ADR-063 — the five configurable human-gate decision points.
 * Each gate guards the exit of exactly one lifecycle stage; a run advancing out
 * of that stage must satisfy the gate's resolved policy first.
 */
public enum ResearchGatePoint {
    /** Methodology selection result, before protocol planning. */
    METHOD_DECISION(ResearchRunStage.METHODOLOGY_SELECTION),
    /** Protocol/plan approval, before source search. */
    PROTOCOL_DECISION(ResearchRunStage.PROTOCOL_PLANNING),
    /** Search strategy / source-set decision, before screening proceeds. */
    SEARCH_DECISION(ResearchRunStage.SOURCE_SEARCH),
    /** Synthesis / evidence-base decision, before argument construction. */
    SYNTHESIS_DECISION(ResearchRunStage.SYNTHESIS),
    /** Argument / drafting posture, before prose drafting. */
    WRITING_DECISION(ResearchRunStage.ARGUMENT_CONSTRUCTION);

    private final ResearchRunStage guardedStageExit;

    ResearchGatePoint(ResearchRunStage guardedStageExit) {
        this.guardedStageExit = guardedStageExit;
    }

    /** The stage whose exit this gate guards. */
    public ResearchRunStage guardedStageExit() {
        return guardedStageExit;
    }

    /** The gate guarding the exit of {@code stage}, if any. */
    public static Optional<ResearchGatePoint> forStageExit(ResearchRunStage stage) {
        for (var gate : values()) {
            if (gate.guardedStageExit == stage) {
                return Optional.of(gate);
            }
        }
        return Optional.empty();
    }
}
