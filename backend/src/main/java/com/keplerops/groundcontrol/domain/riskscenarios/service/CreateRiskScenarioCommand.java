package com.keplerops.groundcontrol.domain.riskscenarios.service;

import java.util.UUID;

public record CreateRiskScenarioCommand(
        UUID projectId,
        String uid,
        String title,
        String threat,
        String method,
        String asset,
        String effect,
        String timeHorizon) {}
