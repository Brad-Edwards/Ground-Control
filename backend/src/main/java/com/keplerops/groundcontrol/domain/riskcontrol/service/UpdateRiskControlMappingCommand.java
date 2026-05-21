package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.util.Map;
import java.util.UUID;

/** Command to update mutable fields on a {@link com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping}. */
public record UpdateRiskControlMappingCommand(
        UUID projectId,
        UUID mappingId,
        String mappingObjective,
        MappingControlRole controlRole,
        String mappingScope,
        UUID methodologyProfileId,
        Map<String, Object> methodologyInfluence) {}
