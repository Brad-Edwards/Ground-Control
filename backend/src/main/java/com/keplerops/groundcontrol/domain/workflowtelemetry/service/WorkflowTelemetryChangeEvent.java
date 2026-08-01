package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import java.util.UUID;

/**
 * Internal post-commit notification that a workflow-run telemetry fact changed (issue #1436).
 *
 * <p>Identifiers only. The notification deliberately carries no entity and no projected field: a
 * consumer re-reads the committed, project-scoped representation for itself, so nothing here can
 * expose uncommitted state or a lazily-unloaded association, and the record stays safe to hand to a
 * consumer running on another thread after the publishing transaction has closed.
 *
 * <p>This is also the seam that keeps single-process fan-out from becoming an architectural
 * commitment (ADR-061 #1436 amendment): a multi-instance deployment replaces the delivery mechanism
 * behind this record with a broker, outbox, or database notification without touching the telemetry
 * aggregate, the REST schemas, or the frontend cache contract.
 */
public record WorkflowTelemetryChangeEvent(Kind kind, String project, UUID runId, UUID entityId) {

    /** Which telemetry fact changed. {@code entityId} identifies that fact; {@code runId} always identifies its run. */
    public enum Kind {
        RUN,
        PHASE_EVENT
    }

    /** The run itself changed; the run is the changed fact, so both identifiers are the run id. */
    public static WorkflowTelemetryChangeEvent run(String project, UUID runId) {
        return new WorkflowTelemetryChangeEvent(Kind.RUN, project, runId, runId);
    }

    /** A phase event was appended to {@code runId}. */
    public static WorkflowTelemetryChangeEvent phaseEvent(String project, UUID runId, UUID eventId) {
        return new WorkflowTelemetryChangeEvent(Kind.PHASE_EVENT, project, runId, eventId);
    }
}
