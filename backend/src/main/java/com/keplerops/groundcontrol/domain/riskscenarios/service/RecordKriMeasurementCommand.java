package com.keplerops.groundcontrol.domain.riskscenarios.service;

import java.math.BigDecimal;
import java.time.Instant;

public record RecordKriMeasurementCommand(BigDecimal value, Instant measuredAt) {}
