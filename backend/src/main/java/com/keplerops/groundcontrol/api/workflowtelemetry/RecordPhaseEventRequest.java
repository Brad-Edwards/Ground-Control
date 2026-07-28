package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/workflow-runs/{runId}/events}.
 *
 * <p>{@code stationId}, {@code stationResult}, and {@code findings} carry the ADR-090 measurement
 * projection (issue #1355). All three are optional: an emitter that cannot attest a verdict omits
 * {@code stationResult} and the service records {@code UNOBSERVED} rather than inferring one from
 * {@code eventType}. A null {@code findings} means nothing was measured; an empty list means the
 * gate ran and found nothing, which is a different fact.
 */
public record RecordPhaseEventRequest(
        @NotBlank @Size(max = 100) String phase,
        @NotNull PhaseEventType eventType,
        @PositiveOrZero Integer cycleIndex,
        @NotNull Instant occurredAt,
        @PositiveOrZero Long durationMs,
        @Size(max = 100) String outcome,
        @NotNull TelemetryProvenance provenance,
        @Size(max = 200) String sourceId,
        @Size(max = 100) String stationId,
        StationResult stationResult,
        @Valid @Size(max = 500) List<GateFindingRequest> findings) {}
