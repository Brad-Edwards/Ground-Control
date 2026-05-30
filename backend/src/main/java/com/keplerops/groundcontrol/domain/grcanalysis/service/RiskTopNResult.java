package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Top-N risk scenarios ranked by latest assessment output for GC-T008. The
 * ranking value is methodology-specific (NIST ordinal risk level, FAIR ALE,
 * ISO-band) so the envelope carries the methodology profile id and family per
 * the ADR-035 result contract and emits a limitation when the same N includes
 * rows produced by different methodology families (the ranking values cannot be
 * directly compared without an explicit conversion rule).
 */
public record RiskTopNResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        List<TopNEntry> entries,
        Counts counts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, int limit, String orderBy) {}

    public record TopNEntry(
            int rank,
            UUID riskAssessmentResultId,
            UUID riskScenarioId,
            String riskScenarioUid,
            String riskScenarioTitle,
            UUID methodologyProfileId,
            String methodologyFamily,
            String rankingMetric,
            String rankingValue,
            String approvalState,
            Instant assessmentAt,
            List<String> limitations) {}

    public record Counts(int totalConsidered, int totalReturned) {}
}
