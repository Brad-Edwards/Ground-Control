package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of a cross-framework gap analysis (GC-I007 / GC-L007).
 *
 * <p>For each framework element discovered through the
 * {@code ComplianceFrameworkMapping} aggregate, produces a {@link GapSeverity}
 * categorization, lists endpoint mappings, and surfaces a coverage-status
 * summary. The minimum-severity filter is recorded in {@code inputs} so the
 * caller can audit what was queried.
 */
public record CrossFrameworkGapResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<FrameworkGap> frameworks,
        Counts counts,
        List<String> limitations) {

    public record Inputs(
            String project, Instant asOf, ComplianceFrameworkIdentifier framework, GapSeverity minSeverity) {}

    public record FrameworkGap(
            ComplianceFrameworkIdentifier framework,
            String frameworkIdentifier,
            String frameworkVersion,
            List<ElementGap> elementGaps,
            Map<String, Integer> bySeverity) {}

    public record ElementGap(
            String frameworkElement,
            GapSeverity severity,
            String coverageStatus,
            List<UUID> requirementIds,
            List<UUID> controlIds,
            int mappingCount) {}

    public record Counts(int totalElements, Map<String, Integer> bySeverity) {}
}
