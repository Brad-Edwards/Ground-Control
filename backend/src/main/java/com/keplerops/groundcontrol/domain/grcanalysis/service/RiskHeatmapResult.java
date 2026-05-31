package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qualitative risk heat map (likelihood × impact) for GC-T008. Carries the
 * ADR-035 methodology-attributed envelope ({@code analysisKind}, {@code project},
 * {@code asOf}, {@code derivationMethod}, {@code methodologyProfileId} /
 * {@code methodologyFamily}, {@code scale}, {@code units}, structured {@code inputs},
 * {@code limitations}) so heat maps requested against a methodology that does
 * not produce ordinal bands (e.g. FAIR) surface an explicit limitation instead
 * of silently coercing quantitative outputs into bands.
 *
 * <p>Cells are addressed by ordinal index (1-based) and the qualitative band
 * label (e.g. {@code HIGH}). Per ADR-035 ordinal bands MUST NOT be normalized
 * into a cross-methodology numeric score without an explicit method label and
 * conversion rule.
 */
public record RiskHeatmapResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        UUID methodologyProfileId,
        String methodologyFamily,
        String scale,
        String units,
        Inputs inputs,
        List<HeatmapCell> cells,
        Counts counts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, UUID methodologyProfileId) {}

    public record HeatmapCell(
            int likelihoodOrdinal,
            String likelihoodBand,
            int impactOrdinal,
            String impactBand,
            int count,
            List<UUID> riskAssessmentResultIds) {}

    public record Counts(
            int totalAssessments,
            int assessmentsPlotted,
            int assessmentsIncompatible,
            Map<String, Integer> byMethodologyFamily) {}
}
