package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Closed domain projection backing the project-scoped live activity REST snapshot. */
public record WorkflowActivitySnapshot(
        Instant asOf,
        long openRunTotal,
        boolean openRunsTruncated,
        List<OpenRun> openRuns,
        List<WorkflowRun> recentlyFinished) {

    public record OpenRun(
            WorkflowRun run,
            String currentPhase,
            String currentPhaseTitle,
            Instant currentPhaseSince,
            Integer currentCycle,
            Duration stallThreshold,
            RoutingObservation routing,
            List<GateAttempt> gates) {}

    public record RoutingObservation(
            String stage,
            String stepAlias,
            CapabilityTier tier,
            String model,
            String expectedModel,
            Boolean modelMatchesExpected,
            Instant occurredAt) {}

    public record GateAttempt(
            String stationId,
            String stationTitle,
            PhaseEventType eventType,
            StationResult stationResult,
            Integer cycleIndex,
            Instant occurredAt,
            Long durationMs,
            long findingCount,
            int findingsDropped) {}
}
