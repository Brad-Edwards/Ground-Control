package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only cross-framework gap-analysis service per GC-I007 / GC-L007 carve-out.
 *
 * <p>Categorizes each (framework, element) tuple from the aggregate by
 * {@link GapSeverity}. The current implementation derives gaps from mapping
 * coverage shape only (FULL / PARTIAL / COMPENSATING) — evidence-freshness
 * propagation lives in the separate {@code EvidenceFreshnessAnalysisService}
 * and is referenced via {@code limitations} on each result. Future expansions
 * (no mapped controls -> CRITICAL, stale evidence -> HIGH) can layer on
 * without re-shaping the result envelope.
 */
@Service
@Transactional(readOnly = true)
public class CrossFrameworkGapService {

    private static final String ANALYSIS_KIND = "cross_framework_gap";
    private static final String DERIVATION_METHOD = "compliance-framework-mapping-gap-projection-v1";
    private static final String STATUS_FULL = "FULL";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_COMPENSATING_ONLY = "COMPENSATING_ONLY";

    private final ComplianceFrameworkMappingService mappingService;
    private final ProjectService projectService;

    public CrossFrameworkGapService(ComplianceFrameworkMappingService mappingService, ProjectService projectService) {
        this.mappingService = mappingService;
        this.projectService = projectService;
    }

    public CrossFrameworkGapResult analyze(
            UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework, GapSeverity minSeverity) {
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        String projectIdentifier = projectService.getById(projectId).getIdentifier();
        GapSeverity effectiveMinSeverity = minSeverity != null ? minSeverity : GapSeverity.NONE;

        List<ComplianceFrameworkMapping> mappings = framework != null
                ? mappingService.listByFramework(projectId, framework)
                : mappingService.listByProject(projectId);

        Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> byFramework = groupByFramework(mappings);

        var frameworkGaps = new ArrayList<CrossFrameworkGapResult.FrameworkGap>();
        var counts = new EnumMap<GapSeverity, Integer>(GapSeverity.class);
        int totalElements = 0;
        var limitations = new ArrayList<String>();

        for (var entry : byFramework.entrySet()) {
            var gap = projectFramework(entry.getKey(), entry.getValue(), effectiveMinSeverity, limitations);
            frameworkGaps.add(gap);
            totalElements += gap.elementGaps().size();
            for (var element : gap.elementGaps()) {
                counts.merge(element.severity(), 1, Integer::sum);
            }
        }

        return new CrossFrameworkGapResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                new CrossFrameworkGapResult.Inputs(projectIdentifier, effectiveAsOf, framework, effectiveMinSeverity),
                List.copyOf(frameworkGaps),
                new CrossFrameworkGapResult.Counts(totalElements, toSeverityMap(counts)),
                List.copyOf(limitations));
    }

    private Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> groupByFramework(
            List<ComplianceFrameworkMapping> mappings) {
        Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> grouped =
                new EnumMap<>(ComplianceFrameworkIdentifier.class);
        for (var m : mappings) {
            grouped.computeIfAbsent(m.getFramework(), k -> new ArrayList<>()).add(m);
        }
        return grouped;
    }

    private CrossFrameworkGapResult.FrameworkGap projectFramework(
            ComplianceFrameworkIdentifier framework,
            List<ComplianceFrameworkMapping> mappings,
            GapSeverity minSeverity,
            List<String> limitations) {
        Map<String, List<ComplianceFrameworkMapping>> byElement = new TreeMap<>();
        String firstExternalIdentifier = null;
        String firstVersion = null;
        for (var m : mappings) {
            byElement
                    .computeIfAbsent(m.getFrameworkElement(), k -> new ArrayList<>())
                    .add(m);
            if (firstExternalIdentifier == null && m.getFrameworkIdentifier() != null) {
                firstExternalIdentifier = m.getFrameworkIdentifier();
            }
            if (firstVersion == null && m.getFrameworkVersion() != null) {
                firstVersion = m.getFrameworkVersion();
            }
            if (m.getFrameworkIdentifier() != null) {
                limitations.add(String.format(
                        "External framework identifier on mapping %s for framework %s element %s",
                        m.getId(), framework.name(), CompliancePostureService.sanitizeForLog(m.getFrameworkElement())));
            }
        }

        var elementGaps = new ArrayList<CrossFrameworkGapResult.ElementGap>();
        var counts = new EnumMap<GapSeverity, Integer>(GapSeverity.class);
        for (var entry : byElement.entrySet()) {
            var element = projectElement(entry.getKey(), entry.getValue());
            if (element.severity().compareTo(minSeverity) > 0) {
                continue;
            }
            elementGaps.add(element);
            counts.merge(element.severity(), 1, Integer::sum);
        }
        return new CrossFrameworkGapResult.FrameworkGap(
                framework, firstExternalIdentifier, firstVersion, List.copyOf(elementGaps), toSeverityMap(counts));
    }

    private CrossFrameworkGapResult.ElementGap projectElement(
            String frameworkElement, List<ComplianceFrameworkMapping> mappings) {
        boolean hasFull = false;
        boolean hasPartial = false;
        boolean hasCompensating = false;
        var requirementIds = new ArrayList<UUID>();
        var controlIds = new ArrayList<UUID>();
        for (var m : mappings) {
            switch (m.getCoverageLevel()) {
                case FULL -> hasFull = true;
                case PARTIAL -> hasPartial = true;
                case COMPENSATING -> hasCompensating = true;
                default -> {
                    /* exhaustive */
                }
            }
            if (m.getRequirement() != null) {
                requirementIds.add(m.getRequirement().getId());
            }
            if (m.getControl() != null) {
                controlIds.add(m.getControl().getId());
            }
        }
        GapSeverity severity;
        String coverageStatus;
        if (hasFull) {
            severity = hasCompensating ? GapSeverity.LOW : GapSeverity.NONE;
            coverageStatus = STATUS_FULL;
        } else if (hasPartial) {
            severity = hasCompensating ? GapSeverity.MEDIUM : GapSeverity.HIGH;
            coverageStatus = STATUS_PARTIAL;
        } else if (hasCompensating) {
            severity = GapSeverity.LOW;
            coverageStatus = STATUS_COMPENSATING_ONLY;
        } else {
            // Unmapped — by construction we only enter this method for elements
            // that DO have at least one mapping. Future evolution can lift the
            // "framework-wide" element catalog (e.g. SOC2 CC1.1..CC9.X) to
            // detect elements with NO mappings (CRITICAL); record as a
            // limitation today.
            severity = GapSeverity.CRITICAL;
            coverageStatus = "UNMAPPED";
        }
        return new CrossFrameworkGapResult.ElementGap(
                frameworkElement,
                severity,
                coverageStatus,
                List.copyOf(requirementIds),
                List.copyOf(controlIds),
                mappings.size());
    }

    private static Map<String, Integer> toSeverityMap(Map<GapSeverity, Integer> source) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var sev : GapSeverity.values()) {
            out.put(sev.name(), source.getOrDefault(sev, 0));
        }
        return out;
    }

    /** Marker for callers — covered-vs-partial-vs-compensating status enum strings. */
    public static List<String> coverageStatusValues() {
        return List.of(STATUS_FULL, STATUS_PARTIAL, STATUS_COMPENSATING_ONLY, "UNMAPPED");
    }
}
