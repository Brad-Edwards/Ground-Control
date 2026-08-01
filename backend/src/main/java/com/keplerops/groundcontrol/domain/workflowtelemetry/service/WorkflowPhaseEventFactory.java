package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryValidation.StepEmission;

/**
 * Builds a {@link WorkflowPhaseEvent} from a validated command (issue #1354). Extracted from
 * {@code WorkflowTelemetryService.recordPhaseEvent} so the service stays within the 500-LOC limit and
 * the entity-population is one reviewable place. The caller has already validated the command, resolved
 * the emitter/station via {@link StepEmission}, and derived the attempt ordinal and source id.
 */
final class WorkflowPhaseEventFactory {

    private WorkflowPhaseEventFactory() {}

    static WorkflowPhaseEvent build(
            RecordPhaseEventCommand command, String project, StepEmission emission, int cycleIndex, String sourceId) {
        var event = new WorkflowPhaseEvent(
                command.runId(),
                project,
                command.phase(),
                command.eventType(),
                command.occurredAt(),
                command.durationMs(),
                command.provenance());
        event.setCycleIndex(cycleIndex);
        event.setOutcome(command.outcome());
        event.setSourceId(sourceId);
        event.setStationId(emission.stationId());
        event.setStationResult(emission.stationResult());
        event.setFindingsDropped(command.findingsDropped());
        event.setEmitter(emission.emitter());
        event.setMeasurementVersion(command.measurementVersion());
        event.setStepAlias(command.stepAlias());
        event.setTier(command.tier());
        event.setModel(command.model());
        event.setExpectedModel(command.expectedModel());
        event.setModelMatchesExpected(command.modelMatchesExpected());
        event.setInputTokens(command.inputTokens());
        event.setOutputTokens(command.outputTokens());
        return event;
    }
}
