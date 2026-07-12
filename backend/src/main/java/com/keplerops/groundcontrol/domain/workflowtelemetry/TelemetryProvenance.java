package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Origin of a workflow-run telemetry fact (issue #859).
 *
 * <p>Every persisted run and phase event records its provenance so stale, partial, and superseded
 * bridge data stays distinguishable from authoritative data. Facts are seeded from GitHub
 * issue-thread {@code gc:} markers ({@link #ISSUE_THREAD}); {@link #TEMPORAL_VISIBILITY} is retained
 * as a historical provenance value for rows recorded before issue #1359 removed the Temporal
 * orchestration lane and is no longer written by any active ingestion path; operators may also enter
 * cost data that no provider API exposes ({@link #MANUAL_IMPORT}).
 */
public enum TelemetryProvenance {
    ISSUE_THREAD,
    TEMPORAL_VISIBILITY,
    MANUAL_IMPORT
}
