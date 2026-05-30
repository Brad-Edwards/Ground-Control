package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.grcanalysis.service.CompliancePostureResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for compliance-posture analysis (GC-I002 / GC-L007 carve-out).
 * Decouples the wire shape from the domain service record so future domain
 * refactors do not silently change the public JSON contract.
 */
public record CompliancePostureResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<FrameworkPosture> frameworks,
        Counts counts,
        List<String> limitations) {

    public static CompliancePostureResponse from(CompliancePostureResult result) {
        return new CompliancePostureResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                Inputs.from(result.inputs()),
                result.frameworks().stream().map(FrameworkPosture::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, ComplianceFrameworkIdentifier framework) {
        public static Inputs from(CompliancePostureResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.framework());
        }
    }

    public record FrameworkPosture(
            ComplianceFrameworkIdentifier framework,
            String frameworkIdentifier,
            String frameworkVersion,
            List<ElementPosture> elements,
            int totalElements,
            int fullCoverageCount,
            int partialCoverageCount,
            int compensatingCoverageCount) {
        public static FrameworkPosture from(CompliancePostureResult.FrameworkPosture p) {
            return new FrameworkPosture(
                    p.framework(),
                    p.frameworkIdentifier(),
                    p.frameworkVersion(),
                    p.elements().stream().map(ElementPosture::from).toList(),
                    p.totalElements(),
                    p.fullCoverageCount(),
                    p.partialCoverageCount(),
                    p.compensatingCoverageCount());
        }
    }

    public record ElementPosture(
            String frameworkElement,
            CoverageLevel coverageLevel,
            List<EndpointMapping> mappings,
            int requirementMappingCount,
            int controlMappingCount) {
        public static ElementPosture from(CompliancePostureResult.ElementPosture e) {
            return new ElementPosture(
                    e.frameworkElement(),
                    e.coverageLevel(),
                    e.mappings().stream().map(EndpointMapping::from).toList(),
                    e.requirementMappingCount(),
                    e.controlMappingCount());
        }
    }

    public record EndpointMapping(
            UUID mappingId, UUID requirementId, UUID controlId, CoverageLevel coverageLevel, String rationale) {
        public static EndpointMapping from(CompliancePostureResult.EndpointMapping m) {
            return new EndpointMapping(
                    m.mappingId(), m.requirementId(), m.controlId(), m.coverageLevel(), m.rationale());
        }
    }

    public record Counts(
            int totalFrameworks, int totalElements, int totalMappings, Map<String, Integer> coverageLevelCounts) {
        public static Counts from(CompliancePostureResult.Counts c) {
            return new Counts(
                    c.totalFrameworks(), c.totalElements(), c.totalMappings(), Map.copyOf(c.coverageLevelCounts()));
        }
    }
}
