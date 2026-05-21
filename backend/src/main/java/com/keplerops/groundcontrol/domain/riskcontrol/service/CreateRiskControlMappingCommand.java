package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.util.Map;
import java.util.UUID;

/**
 * Command to create a {@link com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping}.
 *
 * <p>Exactly one of {@code controlId} / {@code scopedImplementationId} must be non-null (C1 control side).
 * Exactly one of {@code riskScenarioId} / {@code riskRegisterRecordId} must be non-null (C1 risk side).
 * {@code operationalAssetId} is optional (C2).
 */
public record CreateRiskControlMappingCommand(
        UUID projectId,
        /** Catalog control FK — provide this OR scopedImplementationId, never both. */
        UUID controlId,
        /** Scoped implementation FK — provide this OR controlId, never both. */
        UUID scopedImplementationId,
        /** Risk scenario FK — provide this OR riskRegisterRecordId, never both. */
        UUID riskScenarioId,
        /** Risk register record FK — provide this OR riskScenarioId, never both. */
        UUID riskRegisterRecordId,
        /** Optional operational asset context (C2). */
        UUID operationalAssetId,
        /** Mapping-specific control objective (C3). */
        String mappingObjective,
        /** Mapping-specific control role (C3). */
        MappingControlRole controlRole,
        /** Mapping-specific scope (C3). */
        String mappingScope,
        /** Optional methodology profile FK for influence validation (C4). */
        UUID methodologyProfileId,
        /** Optional methodology-specific influence payload (C4). */
        Map<String, Object> methodologyInfluence) {}
