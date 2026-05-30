package com.keplerops.groundcontrol.api.riskscenarios;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record UpdateKeyRiskIndicatorRequest(
        @Size(max = 200) String name,
        String description,
        @Size(max = 50) String metricUnit,
        BigDecimal yellowThreshold,
        BigDecimal redThreshold,
        @Size(max = 20) String direction,
        @Size(max = 200) String owner,
        UUID riskRegisterRecordId,
        UUID riskScenarioId) {}
