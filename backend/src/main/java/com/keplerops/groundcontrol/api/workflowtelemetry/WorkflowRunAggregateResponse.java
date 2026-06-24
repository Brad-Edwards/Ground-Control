package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService.RunAggregate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Aggregate reporting response: run rollups, cost proxies, and per-phase hot spots over a window. */
public record WorkflowRunAggregateResponse(
        Instant from,
        Instant to,
        long totalRuns,
        long mergedRuns,
        long closedRuns,
        long activeRuns,
        long escalatedRuns,
        long abandonedRuns,
        long supersededRuns,
        Double cycleTimeP50Min,
        Double cycleTimeP95Min,
        Double cycleTimeP99Min,
        BigDecimal totalCostProxy,
        BigDecimal mergedCostProxy,
        BigDecimal closedCostProxy,
        BigDecimal costProxyPerMergedRun,
        BigDecimal costProxyPerClosedRun,
        long totalModelInvocations,
        long totalWallClockMinutes,
        long totalTokenUsage,
        List<PhaseHotspotResponse> phaseHotspots) {

    public static WorkflowRunAggregateResponse from(RunAggregate a) {
        var hotspots = a.phaseHotspots().stream()
                .map(h -> new PhaseHotspotResponse(
                        h.phase(),
                        h.eventCount(),
                        h.failedCount(),
                        h.escalatedCount(),
                        h.p50Ms(),
                        h.p95Ms(),
                        h.maxCycleIndex()))
                .toList();
        return new WorkflowRunAggregateResponse(
                a.from(),
                a.to(),
                a.totalRuns(),
                a.mergedRuns(),
                a.closedRuns(),
                a.activeRuns(),
                a.escalatedRuns(),
                a.abandonedRuns(),
                a.supersededRuns(),
                a.cycleTimeP50Min(),
                a.cycleTimeP95Min(),
                a.cycleTimeP99Min(),
                a.totalCostProxy(),
                a.mergedCostProxy(),
                a.closedCostProxy(),
                a.costProxyPerMergedRun(),
                a.costProxyPerClosedRun(),
                a.totalModelInvocations(),
                a.totalWallClockMinutes(),
                a.totalTokenUsage(),
                hotspots);
    }

    /** Per-phase hot-spot row in the aggregate response. */
    public record PhaseHotspotResponse(
            String phase,
            long eventCount,
            long failedCount,
            long escalatedCount,
            Long p50Ms,
            Long p95Ms,
            Integer maxCycleIndex) {}
}
