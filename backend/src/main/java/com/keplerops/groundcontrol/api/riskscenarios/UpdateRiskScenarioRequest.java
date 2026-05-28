package com.keplerops.groundcontrol.api.riskscenarios;

import jakarta.validation.constraints.Size;

public record UpdateRiskScenarioRequest(
        @Size(max = 200) String title,
        @Size(min = 10) String threat,
        @Size(min = 10) String method,
        @Size(min = 10) String asset,
        @Size(min = 10) String effect,
        @Size(max = 100) String timeHorizon) {}
