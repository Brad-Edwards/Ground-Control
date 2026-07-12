package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.util.Map;

public record UpdateRiskControlMappingRequest(
        MappingControlRole controlRole,
        String mappingObjective,
        String mappingScope,
        Map<String, Object> methodologyInfluence) {}
