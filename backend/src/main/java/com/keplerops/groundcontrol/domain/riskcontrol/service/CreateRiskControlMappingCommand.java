package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.util.Map;
import java.util.UUID;

/**
 * Command to create a {@link com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping}.
 *
 * <p>Exactly one of {@code controlId} / {@code scopedImplementationId} must be non-null (C1 control side).
 * Exactly one of {@code threatModelId} / {@code riskScenarioId} must be non-null (C1 analysis side).
 * {@code operationalAssetId} is optional (C2).
 */
public record CreateRiskControlMappingCommand(
        UUID projectId,
        /** Catalog control FK — provide this OR scopedImplementationId, never both. */
        UUID controlId,
        /** Scoped implementation FK — provide this OR controlId, never both. */
        UUID scopedImplementationId,
        /** Risk scenario FK — provide this OR threatModelId, never both. */
        UUID riskScenarioId,
        /** Threat model FK (GC-H006) — provide this OR riskScenarioId, never both. */
        UUID threatModelId,
        /** Optional operational asset context (C2). */
        UUID operationalAssetId,
        /** Mapping-specific control objective (C3). */
        String mappingObjective,
        /** Mapping-specific control role (C3). */
        MappingControlRole controlRole,
        /** Mapping-specific scope (C3). */
        String mappingScope,
        /** Optional free-form methodology-influence payload (C4); not schema-validated. */
        Map<String, Object> methodologyInfluence) {}
