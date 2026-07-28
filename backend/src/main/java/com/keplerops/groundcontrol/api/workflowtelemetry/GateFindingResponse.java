package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a {@link WorkflowGateFinding}.
 *
 * <p>Carries the same bounded facts the row does. There is no title, body, path, or line here for
 * the same reason there is none on the entity: the ADR-029 issue thread is the narrative record,
 * and a measurement surface that echoed review prose would become a second one.
 */
public record GateFindingResponse(
        UUID id,
        UUID runId,
        UUID phaseEventId,
        String project,
        String stationId,
        FindingSourceKind sourceKind,
        String sourceId,
        String findingKey,
        String category,
        String severity,
        String classification,
        FindingDisposition disposition,
        Instant occurredAt) {

    public static GateFindingResponse from(WorkflowGateFinding f) {
        return new GateFindingResponse(
                f.getId(),
                f.getRunId(),
                f.getPhaseEventId(),
                f.getProject(),
                f.getStationId(),
                f.getSourceKind(),
                f.getSourceId(),
                f.getFindingKey(),
                f.getCategory(),
                f.getSeverity(),
                f.getClassification(),
                f.getDisposition(),
                f.getOccurredAt());
    }
}
