package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
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
 *
 * <p>{@code emitter} and the ADR-036 step facts below it ({@code measurementVersion} through
 * {@code outputTokens}) carry a durable ADR-036 step observation (ADR-090 amendment, issue #1354).
 * They make the run-scoped event surface the queryable per-step record: a consumer selects the
 * {@code ADR036_STEP_JSONL} emitter to read routed-step economics, while lifecycle/gate consumers
 * select the ADR-061 emitter. This same record is what the SSE transport serialises, so the durable
 * step observation is visible live without a stream-only schema.
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
        int findingsDropped,
        PhaseEventEmitter emitter,
        String measurementVersion,
        String stepAlias,
        CapabilityTier tier,
        String model,
        String expectedModel,
        Boolean modelMatchesExpected,
        Long inputTokens,
        Long outputTokens) {

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
                event.getFindingsDropped(),
                event.getEmitter(),
                event.getMeasurementVersion(),
                event.getStepAlias(),
                event.getTier(),
                event.getModel(),
                event.getExpectedModel(),
                event.getModelMatchesExpected(),
                event.getInputTokens(),
                event.getOutputTokens());
    }
}
