package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Structured result of continuous compliance monitoring per GC-I004. Uses ADR-058
 * vocabulary ({@code impact_set}, {@code gap_set}, {@code stale_set}) as
 * structured lists rather than prose.
 */
public record ComplianceMonitoringResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<ImpactItem> impactSet,
        List<GapItem> gapSet,
        List<StaleItem> staleSet,
        DriftCauseCounts driftCauseCounts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, int freshnessWindowDays) {}

    public record ImpactItem(
            String driftCause,
            String entityType,
            UUID entityId,
            String entityUid,
            Instant detectedAt,
            String summary) {}

    public record GapItem(String gapKind, String entityType, UUID entityId, String entityUid, String summary) {}

    public record StaleItem(String sourceKind, UUID entityId, String entityUid, String state, Instant detectedAt) {}

    public record DriftCauseCounts(int controlModification, int artifactGraphChange, int evidenceExpiration) {}
}
