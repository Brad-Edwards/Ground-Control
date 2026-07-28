package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

/** Per-phase hot-spot row: counts, failed/escalation counts, p50/p95 duration, and max cycle index. */
public record PhaseHotspot(
        String phase,
        long eventCount,
        long failedCount,
        long escalatedCount,
        Long p50Ms,
        Long p95Ms,
        Integer maxCycleIndex) {}
