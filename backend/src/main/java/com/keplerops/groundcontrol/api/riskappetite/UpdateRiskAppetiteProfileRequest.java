package com.keplerops.groundcontrol.api.riskappetite;

import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Update-request body for {@code PUT /api/v1/risk-appetite-profiles/{id}} (GC-T005). Null fields are left unchanged. */
public record UpdateRiskAppetiteProfileRequest(
        @Size(max = 200) String name,
        @Size(max = 50) String version,
        MethodologyFamily methodologyFamily,
        String appetiteStatement,
        @Valid List<ToleranceThreshold> toleranceThresholds,
        RiskAppetiteProfileStatus status,
        Instant effectiveFrom,
        Instant effectiveTo) {}
