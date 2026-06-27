package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Kind of phase/gate event captured for a workflow run (issue #859).
 *
 * <p>Counts of {@link #FAILED} per phase drive failed-gate reporting; {@link #ESCALATED} marks a
 * human-escalation point; {@code cycle_index} on the event distinguishes repeated review/CI cycles.
 */
public enum PhaseEventType {
    STARTED,
    COMPLETED,
    FAILED,
    ESCALATED,
    SKIPPED
}
