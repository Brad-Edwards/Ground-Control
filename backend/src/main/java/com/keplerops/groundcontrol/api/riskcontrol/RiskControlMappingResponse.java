package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RiskControlMappingResponse(
        UUID id,
        UUID projectId,
        // Control-side
        UUID controlId,
        UUID scopedImplementationId,
        // Analysis-side
        UUID riskScenarioId,
        UUID riskRegisterRecordId,
        UUID threatModelId,
        // C2
        UUID operationalAssetId,
        // C3
        String mappingObjective,
        MappingControlRole controlRole,
        String mappingScope,
        // C4
        UUID methodologyProfileId,
        Map<String, Object> methodologyInfluence,
        Instant createdAt,
        Instant updatedAt) {

    public static RiskControlMappingResponse from(RiskControlMapping mapping) {
        return new RiskControlMappingResponse(
                mapping.getId(),
                mapping.getProject().getId(),
                mapping.getControl() != null ? mapping.getControl().getId() : null,
                mapping.getScopedImplementation() != null
                        ? mapping.getScopedImplementation().getId()
                        : null,
                mapping.getRiskScenario() != null ? mapping.getRiskScenario().getId() : null,
                mapping.getRiskRegisterRecord() != null
                        ? mapping.getRiskRegisterRecord().getId()
                        : null,
                mapping.getThreatModel() != null ? mapping.getThreatModel().getId() : null,
                mapping.getOperationalAsset() != null
                        ? mapping.getOperationalAsset().getId()
                        : null,
                mapping.getMappingObjective(),
                mapping.getControlRole(),
                mapping.getMappingScope(),
                mapping.getMethodologyProfile() != null
                        ? mapping.getMethodologyProfile().getId()
                        : null,
                mapping.getMethodologyInfluence(),
                mapping.getCreatedAt(),
                mapping.getUpdatedAt());
    }
}
