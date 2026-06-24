package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Origin of a workflow-run telemetry fact (issue #859).
 *
 * <p>Every persisted run and phase event records its provenance so stale, partial, and superseded
 * bridge data stays distinguishable from authoritative data. During the transition bridge facts are
 * seeded from GitHub issue-thread {@code gc:} markers ({@link #ISSUE_THREAD}); once GC-O009 owns
 * execution end to end Temporal Visibility becomes the source of truth ({@link #TEMPORAL_VISIBILITY});
 * operators may also enter cost data that no provider API exposes ({@link #MANUAL_IMPORT}).
 */
public enum TelemetryProvenance {
    ISSUE_THREAD,
    TEMPORAL_VISIBILITY,
    MANUAL_IMPORT
}
