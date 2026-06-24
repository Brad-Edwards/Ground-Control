package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable command to import manual/nullable economics for an existing run — the path for cost data
 * no provider API exposes reliably per run. Only the provided (non-null) fields are applied.
 */
public record ImportRunCostCommand(
        UUID runId,
        String project,
        String provider,
        String model,
        Integer modelInvocationCount,
        Integer wallClockMinutes,
        BigDecimal costProxy,
        String costCurrency,
        Long tokenUsage) {}
