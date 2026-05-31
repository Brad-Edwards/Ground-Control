package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Distribution of risk register records and risk scenarios over a chosen
 * grouping axis (category tag, status, owner, asset criticality). Returns the
 * GC-T008 methodology-attributed envelope per ADR-035 — distributions are
 * methodology-agnostic counts so the result envelope makes the
 * {@code derivationMethod} explicit and emits a limitation for groupings that
 * are not populated for the project's records (e.g. {@code OWNER} when no
 * register row supplies an owner).
 */
public record RiskDistributionResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        Inputs inputs,
        List<DistributionBucket> buckets,
        Counts counts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, String groupBy) {}

    public record DistributionBucket(String key, String label, int count) {}

    public record Counts(int totalRecords, int recordsCounted, int recordsUnclassified, Map<String, Integer> totals) {}
}
