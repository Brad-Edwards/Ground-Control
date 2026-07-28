package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Aggregate reporting result over a scoped window. Cost-per-run ratios are null when the outcome count is zero. */
public record RunAggregate(
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
        List<PhaseHotspot> phaseHotspots) {}
