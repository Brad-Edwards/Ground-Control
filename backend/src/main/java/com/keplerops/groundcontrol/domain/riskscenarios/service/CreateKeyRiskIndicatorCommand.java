package com.keplerops.groundcontrol.domain.riskscenarios.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateKeyRiskIndicatorCommand(
        UUID projectId,
        String uid,
        String name,
        String description,
        String metricUnit,
        BigDecimal yellowThreshold,
        BigDecimal redThreshold,
        String direction,
        String owner,
        UUID riskRegisterRecordId,
        UUID riskScenarioId) {}
