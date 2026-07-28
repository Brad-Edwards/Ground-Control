package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a {@link WorkflowPhaseEvent}.
 *
 * <p>{@code stationId} and {@code stationResult} (issue #1355) are exposed alongside the existing
 * lifecycle fields rather than replacing them: a consumer must be able to see that {@code eventType}
 * and {@code stationResult} are different answers to different questions. Omitting the verdict here
 * would leave every reader — including the SSE transport, which serialises this same record — able
 * to observe only that a phase completed.
 */
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
        TelemetryProvenance provenance,
        String sourceId,
        String stationId,
        StationResult stationResult,
        /** Findings the emitter's cap discarded; without it a truncated batch reads as complete. */
        int findingsDropped) {

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
                event.getProvenance(),
                event.getSourceId(),
                event.getStationId(),
                event.getStationResult(),
                event.getFindingsDropped());
    }
}
