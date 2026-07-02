package com.keplerops.groundcontrol.domain.controlidentification.service;

import java.util.UUID;

/**
 * Outcome of confirming a candidate control against a threat (GC-GRC-008 clause c). Records the ids of
 * the canonical mapping aggregates that now carry the confirmed relationship — a
 * {@code RiskControlMapping} (queryable coverage) and a {@code ThreatModelLink MITIGATED_BY}
 * (threat-owned traversal) — plus whether each was newly created (confirmation is idempotent).
 */
public record ControlMappingConfirmation(
        UUID riskControlMappingId, UUID threatModelLinkId, boolean mappingCreated, boolean linkCreated) {}
