package com.keplerops.groundcontrol.domain.riskappetite.service;

import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;

/**
 * Immutable update input for a {@link com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile}
 * (GC-T005). Null fields leave the existing value untouched; {@code appetiteKey} is immutable and
 * therefore absent.
 */
public record UpdateRiskAppetiteProfileCommand(
        String name,
        String version,
        MethodologyFamily methodologyFamily,
        String appetiteStatement,
        List<ToleranceThreshold> toleranceThresholds,
        RiskAppetiteProfileStatus status,
        Instant effectiveFrom,
        Instant effectiveTo) {}
