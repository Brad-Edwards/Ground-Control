package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of a compliance-posture analysis (GC-I002 / GC-L007 carve-out).
 *
 * <p>Carries the methodology-attributed envelope from the GC-L007 preflight
 * "Result Contract" — explicit {@code frameworkIdentifier}/{@code frameworkVersion},
 * {@code inputs} reflecting the mapping aggregate version, {@code outputs} with
 * per-element posture, and a {@code limitations} array for external framework
 * identifiers.
 */
public record CompliancePostureResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<FrameworkPosture> frameworks,
        Counts counts,
        List<String> limitations) {

    public record Inputs(String project, Instant asOf, ComplianceFrameworkIdentifier framework) {}

    public record FrameworkPosture(
            ComplianceFrameworkIdentifier framework,
            String frameworkIdentifier,
            String frameworkVersion,
            List<ElementPosture> elements,
            int totalElements,
            int fullCoverageCount,
            int partialCoverageCount,
            int compensatingCoverageCount) {}

    public record ElementPosture(
            String frameworkElement,
            CoverageLevel coverageLevel,
            List<EndpointMapping> mappings,
            int requirementMappingCount,
            int controlMappingCount) {}

    public record EndpointMapping(
            UUID mappingId, UUID requirementId, UUID controlId, CoverageLevel coverageLevel, String rationale) {}

    public record Counts(
            int totalFrameworks, int totalElements, int totalMappings, Map<String, Integer> coverageLevelCounts) {}
}
