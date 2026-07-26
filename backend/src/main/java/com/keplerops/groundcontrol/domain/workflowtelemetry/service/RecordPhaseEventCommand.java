package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable command to append one phase/gate event to an existing run. {@code project} scopes the
 * run lookup so a caller cannot append events to another project's run (issue #859 security review).
 *
 * <p>{@code cycleIndex} and {@code sourceId} are both optional (issue #1435): an emitter supplies
 * them when it can authoritatively attest the attempt order and the fact's identity, and the service
 * derives them otherwise. See {@code WorkflowTelemetryService.recordPhaseEvent} for the rules.
 */
public record RecordPhaseEventCommand(
        UUID runId,
        String project,
        String phase,
        PhaseEventType eventType,
        Integer cycleIndex,
        Instant occurredAt,
        Long durationMs,
        String outcome,
        TelemetryProvenance provenance,
        String sourceId) {}
