package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import java.time.Instant;
import java.util.UUID;

/** Read projection of a {@link WorkflowPhaseEvent}. */
public record PhaseEventResponse(
        UUID id,
        UUID runId,
        String project,
        String phase,
        PhaseEventType eventType,
        Integer cycleIndex,
        Instant occurredAt,
        Long durationMs,
        String outcome,
        TelemetryProvenance provenance) {

    public static PhaseEventResponse from(WorkflowPhaseEvent event) {
        return new PhaseEventResponse(
                event.getId(),
                event.getRunId(),
                event.getProject(),
                event.getPhase(),
                event.getEventType(),
                event.getCycleIndex(),
                event.getOccurredAt(),
                event.getDurationMs(),
                event.getOutcome(),
                event.getProvenance());
    }
}
