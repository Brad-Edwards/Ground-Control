package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Bounded gate-state read model of a running {@code /implement} workflow (GC-O009 (c), GC-Q016 (a)/(b)).
 * Queried from the workflow (never reconstructed from raw Temporal history) so the operations console
 * can render the current phase, whether the single human merge gate is pending, and which gate — if any
 * — is escalated awaiting an operator decision.
 *
 * <p>Ids and closed enums only; no prose, secrets, or provider payloads. Schema:
 * {@code gc.workflow.implement-workflow.v1#/$defs/GateState}.
 *
 * @param phase current execution phase (always present)
 * @param outcome terminal/interim outcome, or {@code null} while the run is mid-phase
 * @param waitingForMerge {@code true} while the run is blocked on the single synchronous human gate (PR
 *     merge), i.e. ready-for-review and awaiting the authoritative GitHub merge event
 * @param escalatedPhase the phase whose gate is escalated awaiting an operator signal, or {@code null}
 * @param escalatedReviewer the reviewer whose review gate is escalated, or {@code null}
 */
public record GateState(
        ImplementPhase phase,
        ImplementOutcome outcome,
        boolean waitingForMerge,
        ImplementPhase escalatedPhase,
        ReviewerKind escalatedReviewer) {}
