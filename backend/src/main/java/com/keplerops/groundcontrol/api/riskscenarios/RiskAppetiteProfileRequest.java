package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RiskAppetiteProfileRequest(
        @NotBlank @Size(max = 100) String profileKey,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String version,
        String appetiteStatement,
        @Size(max = 200) String owner,
        Boolean active,
        @Valid List<@NotNull RiskAppetiteTolerance> tolerances) {}
