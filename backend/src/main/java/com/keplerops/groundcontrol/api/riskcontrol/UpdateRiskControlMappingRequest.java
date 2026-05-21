package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.util.Map;
import java.util.UUID;

public record UpdateRiskControlMappingRequest(
        MappingControlRole controlRole,
        String mappingObjective,
        String mappingScope,
        UUID methodologyProfileId,
        Map<String, Object> methodologyInfluence) {}
