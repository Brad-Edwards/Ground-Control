package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import java.util.List;
import java.util.UUID;

public record CreateRiskAppetiteProfileCommand(
        UUID projectId,
        String profileKey,
        String name,
        String version,
        String appetiteStatement,
        String owner,
        Boolean active,
        List<RiskAppetiteTolerance> tolerances) {}
