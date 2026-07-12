package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record RiskControlMappingRequest(
        /** Exactly one of controlId/scopedImplementationId required. */
        UUID controlId,
        UUID scopedImplementationId,
        /** Exactly one of riskScenarioId/threatModelId required. */
        UUID riskScenarioId,
        UUID threatModelId,
        UUID operationalAssetId,
        String mappingObjective,
        @NotNull MappingControlRole controlRole,
        String mappingScope,
        Map<String, Object> methodologyInfluence) {}
