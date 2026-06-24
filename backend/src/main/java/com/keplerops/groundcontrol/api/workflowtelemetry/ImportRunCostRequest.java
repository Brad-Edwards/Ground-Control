package com.keplerops.groundcontrol.api.workflowtelemetry;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request body for {@code POST /api/v1/workflow-runs/{runId}/cost} (manual economics import). */
public record ImportRunCostRequest(
        @Size(max = 100) String provider,
        @Size(max = 200) String model,
        @PositiveOrZero Integer modelInvocationCount,
        @PositiveOrZero Integer wallClockMinutes,
        @PositiveOrZero @Digits(integer = 10, fraction = 4) BigDecimal costProxy,
        @Size(max = 10) String costCurrency,
        @PositiveOrZero Long tokenUsage) {}
