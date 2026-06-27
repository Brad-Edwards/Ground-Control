package com.keplerops.groundcontrol.api.research;

import jakarta.validation.constraints.PositiveOrZero;

/** Record observed usage/cost for a run (separate from budget caps). */
public record RecordUsageRequest(@PositiveOrZero long tokens, @PositiveOrZero long costUsdMicros) {}
