package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request body for {@code POST /api/v1/workflow-runs/{runId}/events}. */
public record RecordPhaseEventRequest(
        @NotBlank @Size(max = 100) String phase,
        @NotNull PhaseEventType eventType,
        @PositiveOrZero Integer cycleIndex,
        @NotNull Instant occurredAt,
        @PositiveOrZero Long durationMs,
        @Size(max = 100) String outcome,
        @NotNull TelemetryProvenance provenance,
        @Size(max = 200) String sourceId) {}
