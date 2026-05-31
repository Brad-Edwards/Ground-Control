package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only compliance-posture analysis service per GC-I002 / GC-L007 carve-out.
 *
 * <p>Drives off the {@link ComplianceFrameworkMapping} aggregate and produces
 * a per-framework, per-element coverage rollup so an agent can answer
 * "what is our SOC2 readiness?" with a single MCP call. Limitations are
 * appended when a mapping carries an external {@code frameworkIdentifier}
 * string (genuine externals not in the seeded enum) so callers know the
 * row's framework identity is human-attributed.
 */
@Service
@Transactional(readOnly = true)
public class CompliancePostureService {

    private static final String ANALYSIS_KIND = "compliance_posture";
    private static final String DERIVATION_METHOD = "compliance-framework-mapping-projection-v1";

    private final ComplianceFrameworkMappingService mappingService;
    private final ProjectService projectService;

    public CompliancePostureService(ComplianceFrameworkMappingService mappingService, ProjectService projectService) {
        this.mappingService = mappingService;
        this.projectService = projectService;
    }

    public CompliancePostureResult analyze(UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework) {
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        String projectIdentifier = projectService.getById(projectId).getIdentifier();

        List<ComplianceFrameworkMapping> mappings = framework != null
                ? mappingService.listByFramework(projectId, framework)
                : mappingService.listByProject(projectId);

        Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> byFramework = groupByFramework(mappings);

        var frameworkPostures = new ArrayList<CompliancePostureResult.FrameworkPosture>();
        var coverageLevelCounts = new EnumMap<CoverageLevel, Integer>(CoverageLevel.class);
        int totalMappings = 0;
        int totalElements = 0;
        var limitations = new ArrayList<String>();

        for (var entry : byFramework.entrySet()) {
            var posture = projectFramework(entry.getKey(), entry.getValue(), limitations);
            frameworkPostures.add(posture);
            totalElements += posture.totalElements();
            for (var element : posture.elements()) {
                for (var endpoint : element.mappings()) {
                    coverageLevelCounts.merge(endpoint.coverageLevel(), 1, Integer::sum);
                    totalMappings++;
                }
            }
        }

        var counts = new CompliancePostureResult.Counts(
                frameworkPostures.size(), totalElements, totalMappings, toStringIntegerMap(coverageLevelCounts));

        return new CompliancePostureResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                new CompliancePostureResult.Inputs(projectIdentifier, effectiveAsOf, framework),
                List.copyOf(frameworkPostures),
                counts,
                List.copyOf(limitations));
    }

    private Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> groupByFramework(
            List<ComplianceFrameworkMapping> mappings) {
        // EnumMap preserves declaration order (SOC2, SOX, ISO_27001, NIST_CSF, PCI_DSS),
        // giving a stable per-framework rollup order.
        Map<ComplianceFrameworkIdentifier, List<ComplianceFrameworkMapping>> grouped =
                new EnumMap<>(ComplianceFrameworkIdentifier.class);
        for (var m : mappings) {
            grouped.computeIfAbsent(m.getFramework(), k -> new ArrayList<>()).add(m);
        }
        return grouped;
    }

    private CompliancePostureResult.FrameworkPosture projectFramework(
            ComplianceFrameworkIdentifier framework,
            List<ComplianceFrameworkMapping> mappings,
            List<String> limitations) {
        // Group by frameworkElement, in deterministic (TreeMap) order. Capture
        // distinct external identifiers and versions so the result envelope can
        // surface a single posture row per framework even when a mapping author
        // supplied a free-form identifier or per-row version.
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
                        m.getId(), framework.name(), sanitizeForLog(m.getFrameworkElement())));
            }
        }
        var elements = new ArrayList<CompliancePostureResult.ElementPosture>();
        int fullCount = 0;
        int partialCount = 0;
        int compensatingCount = 0;
        for (var entry : byElement.entrySet()) {
            var element = projectElement(entry.getKey(), entry.getValue());
            elements.add(element);
            switch (element.coverageLevel()) {
                case FULL -> fullCount++;
                case PARTIAL -> partialCount++;
                case COMPENSATING -> compensatingCount++;
                default -> {
                    /* exhaustive */
                }
            }
        }
        return new CompliancePostureResult.FrameworkPosture(
                framework,
                firstExternalIdentifier,
                firstVersion,
                List.copyOf(elements),
                elements.size(),
                fullCount,
                partialCount,
                compensatingCount);
    }

    private CompliancePostureResult.ElementPosture projectElement(
            String frameworkElement, List<ComplianceFrameworkMapping> mappings) {
        // Stable order: requirement-side endpoints first, then control-side; by id.
        mappings.sort(Comparator.comparing((ComplianceFrameworkMapping m) -> m.getRequirement() != null ? 0 : 1)
                .thenComparing(m -> m.getId().toString()));
        var endpoints = buildEndpoints(mappings);
        int requirementCount =
                (int) mappings.stream().filter(m -> m.getRequirement() != null).count();
        int controlCount =
                (int) mappings.stream().filter(m -> m.getControl() != null).count();
        CoverageLevel elementCoverage = deriveCoverageLevel(mappings);
        return new CompliancePostureResult.ElementPosture(
                frameworkElement, elementCoverage, List.copyOf(endpoints), requirementCount, controlCount);
    }

    private List<CompliancePostureResult.EndpointMapping> buildEndpoints(List<ComplianceFrameworkMapping> mappings) {
        var endpoints = new ArrayList<CompliancePostureResult.EndpointMapping>();
        for (var m : mappings) {
            endpoints.add(new CompliancePostureResult.EndpointMapping(
                    m.getId(),
                    m.getRequirement() != null ? m.getRequirement().getId() : null,
                    m.getControl() != null ? m.getControl().getId() : null,
                    m.getCoverageLevel(),
                    m.getRationale()));
        }
        return endpoints;
    }

    /**
     * Derives the aggregate coverage level for a framework element from the
     * individual mapping coverage levels. FULL takes precedence over PARTIAL
     * which takes precedence over COMPENSATING. An empty list is impossible
     * here (we group by existing mappings), but defaults to PARTIAL if reached.
     */
    private static CoverageLevel deriveCoverageLevel(List<ComplianceFrameworkMapping> mappings) {
        boolean hasFull = false;
        boolean hasPartial = false;
        boolean hasCompensating = false;
        for (var m : mappings) {
            switch (m.getCoverageLevel()) {
                case FULL -> hasFull = true;
                case PARTIAL -> hasPartial = true;
                case COMPENSATING -> hasCompensating = true;
                default -> {
                    /* exhaustive */
                }
            }
        }
        if (hasFull) {
            return CoverageLevel.FULL;
        }
        if (hasPartial) {
            return CoverageLevel.PARTIAL;
        }
        return hasCompensating ? CoverageLevel.COMPENSATING : CoverageLevel.PARTIAL;
    }

    private static Map<String, Integer> toStringIntegerMap(Map<CoverageLevel, Integer> source) {
        var out = new LinkedHashMap<String, Integer>();
        for (var level : CoverageLevel.values()) {
            out.put(level.name(), source.getOrDefault(level, 0));
        }
        return out;
    }

    /**
     * Replace control chars and embedded log-injection vectors in a framework
     * element string before it lands in a limitation message. Per the cluster
     * security note: gap-severity arrays in limitations are guarded against
     * log-injection from external framework identifiers.
     */
    static String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
