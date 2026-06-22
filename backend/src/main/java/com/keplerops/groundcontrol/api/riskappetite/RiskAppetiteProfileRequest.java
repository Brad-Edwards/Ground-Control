package com.keplerops.groundcontrol.api.riskappetite;

import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Create-request body for {@code POST /api/v1/risk-appetite-profiles} (GC-T005). */
public record RiskAppetiteProfileRequest(
        @NotBlank @Size(max = 100) String appetiteKey,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String version,
        @NotNull MethodologyFamily methodologyFamily,
        String appetiteStatement,
        @Valid List<ToleranceThreshold> toleranceThresholds,
        RiskAppetiteProfileStatus status,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo) {}
