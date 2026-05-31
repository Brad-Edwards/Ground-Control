package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import java.util.List;

public record UpdateRiskAppetiteProfileCommand(
        String name,
        String version,
        String appetiteStatement,
        String owner,
        Boolean active,
        List<RiskAppetiteTolerance> tolerances) {}
