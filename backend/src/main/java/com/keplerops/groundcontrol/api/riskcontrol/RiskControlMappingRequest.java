package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record RiskControlMappingRequest(
        /** Exactly one of controlId/scopedImplementationId required. */
        UUID controlId,
        UUID scopedImplementationId,
        /** Exactly one of riskScenarioId/riskRegisterRecordId required. */
        UUID riskScenarioId,
        UUID riskRegisterRecordId,
        UUID operationalAssetId,
        String mappingObjective,
        @NotNull MappingControlRole controlRole,
        String mappingScope,
        UUID methodologyProfileId,
        Map<String, Object> methodologyInfluence) {}
