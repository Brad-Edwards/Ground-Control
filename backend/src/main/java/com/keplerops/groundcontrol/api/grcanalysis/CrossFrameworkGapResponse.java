package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import com.keplerops.groundcontrol.domain.grcanalysis.service.CrossFrameworkGapResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for cross-framework gap analysis (GC-I007 / GC-L007 carve-out).
 * Decouples the wire shape from the domain service record so future domain
 * refactors do not silently change the public JSON contract.
 */
public record CrossFrameworkGapResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<FrameworkGap> frameworks,
        Counts counts,
        List<String> limitations) {

    public static CrossFrameworkGapResponse from(CrossFrameworkGapResult result) {
        return new CrossFrameworkGapResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                Inputs.from(result.inputs()),
                result.frameworks().stream().map(FrameworkGap::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(
            String project, Instant asOf, ComplianceFrameworkIdentifier framework, GapSeverity minSeverity) {
        public static Inputs from(CrossFrameworkGapResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.framework(), inputs.minSeverity());
        }
    }

    public record FrameworkGap(
            ComplianceFrameworkIdentifier framework,
            String frameworkIdentifier,
            String frameworkVersion,
            List<ElementGap> elementGaps,
            Map<String, Integer> bySeverity) {
        public static FrameworkGap from(CrossFrameworkGapResult.FrameworkGap g) {
            return new FrameworkGap(
                    g.framework(),
                    g.frameworkIdentifier(),
                    g.frameworkVersion(),
                    g.elementGaps().stream().map(ElementGap::from).toList(),
                    Map.copyOf(g.bySeverity()));
        }
    }

    public record ElementGap(
            String frameworkElement,
            GapSeverity severity,
            String coverageStatus,
            List<UUID> requirementIds,
            List<UUID> controlIds,
            int mappingCount) {
        public static ElementGap from(CrossFrameworkGapResult.ElementGap e) {
            return new ElementGap(
                    e.frameworkElement(),
                    e.severity(),
                    e.coverageStatus(),
                    List.copyOf(e.requirementIds()),
                    List.copyOf(e.controlIds()),
                    e.mappingCount());
        }
    }

    public record Counts(int totalElements, Map<String, Integer> bySeverity) {
        public static Counts from(CrossFrameworkGapResult.Counts c) {
            return new Counts(c.totalElements(), Map.copyOf(c.bySeverity()));
        }
    }
}
