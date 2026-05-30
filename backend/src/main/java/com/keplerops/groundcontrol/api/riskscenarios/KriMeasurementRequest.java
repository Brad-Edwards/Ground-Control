package com.keplerops.groundcontrol.api.riskscenarios;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record KriMeasurementRequest(@NotNull BigDecimal value, Instant measuredAt) {}
