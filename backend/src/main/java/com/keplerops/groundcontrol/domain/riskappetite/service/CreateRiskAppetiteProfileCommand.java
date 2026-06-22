package com.keplerops.groundcontrol.domain.riskappetite.service;

import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable write-path input for creating a {@link com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile} (GC-T005). */
public record CreateRiskAppetiteProfileCommand(
        UUID projectId,
        String appetiteKey,
        String name,
        String version,
        MethodologyFamily methodologyFamily,
        String appetiteStatement,
        List<ToleranceThreshold> toleranceThresholds,
        RiskAppetiteProfileStatus status,
        Instant effectiveFrom,
        Instant effectiveTo) {}
