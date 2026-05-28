package com.keplerops.groundcontrol.api.riskscenarios;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RiskScenarioRequest(
        @NotBlank @Size(max = 20) String uid,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(min = 10) String threat,
        @NotBlank @Size(min = 10) String method,
        @NotBlank @Size(min = 10) String asset,
        @NotBlank @Size(min = 10) String effect,
        @NotBlank @Size(max = 100) String timeHorizon) {}
