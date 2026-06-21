package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAIR-CAM control analytics per GC-I017. Reads existing RiskControlMapping,
 * ControlEffectivenessAssessment, and ControlTest rows to derive control-level
 * analytics in the FAIR-CAM framework.
 *
 * <p>This service is read-only — it never mutates any entity.
 */
@Service
@Transactional(readOnly = true)
public class FairCamControlAnalyticsService {

    static final String ANALYSIS_KIND = "fair_cam_control_analytics";
    static final String DERIVATION_METHOD = "fair-cam-control-analytics-v1";

    private static final String KEY_FAIR_CAM_DOMAIN = "fair_cam_domain";
    private static final String SCALE_ORDINAL = "ordinal";
    private static final String UNITS_RATING = "ControlEffectivenessRating";
    private static final String NOT_DERIVABLE_NO_ASSESSMENT = "not-derivable: no assessment";

    private final RiskControlMappingRepository mappingRepo;
    private final ControlEffectivenessAssessmentRepository assessmentRepo;
    private final ControlTestRepository testRepo;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public FairCamControlAnalyticsService(
            RiskControlMappingRepository mappingRepo,
            ControlEffectivenessAssessmentRepository assessmentRepo,
            ControlTestRepository testRepo,
            ProjectRepository projectRepository,
            Clock clock) {
        this.mappingRepo = mappingRepo;
        this.assessmentRepo = assessmentRepo;
        this.testRepo = testRepo;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    public FairCamControlAnalyticsResult analyze(UUID projectId, FairCamControlAnalyticsQuery query) {

        Instant effectiveAsOf = query.asOf() == null ? Instant.now(clock) : query.asOf();
        String projectIdentifier = resolveProjectIdentifier(projectId);
        LocalDate asOfDate = effectiveAsOf.atZone(ZoneOffset.UTC).toLocalDate();

        List<RiskControlMapping> mappings = loadMappings(projectId, query);

        // Load assessments and tests project-wide to avoid N+1
        List<ControlEffectivenessAssessment> allAssessments =
                assessmentRepo.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        projectId, asOfDate);
        List<ControlTest> allTests =
                testRepo.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(projectId, asOfDate);

        Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl = allAssessments.stream()
                .collect(Collectors.groupingBy(a -> a.getControl().getId()));
        Map<UUID, List<ControlTest>> testsByControl = allTests.stream()
                .collect(Collectors.groupingBy(t -> t.getControl().getId()));

        // Group mappings by control endpoint key (controlId or scopedImplementationId)
        // to compute coverage (count of distinct analysis endpoints per control endpoint)
        Map<UUID, List<RiskControlMapping>> byControlEndpoint = new LinkedHashMap<>();
        for (RiskControlMapping m : mappings) {
            UUID key = m.isControlSide()
                    ? m.getControl().getId()
                    : m.getScopedImplementation().getId();
            byControlEndpoint.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        List<FairCamControlAnalyticsResult.ControlAnalyticsItem> items = new ArrayList<>();
        Map<String, Integer> byDomainCounts = new LinkedHashMap<>();
        int withLimitations = 0;

        for (Map.Entry<UUID, List<RiskControlMapping>> entry : byControlEndpoint.entrySet()) {
            List<RiskControlMapping> groupMappings = entry.getValue();
            RiskControlMapping first = groupMappings.get(0);

            FairCamControlAnalyticsResult.ControlAnalyticsItem item = buildItem(
                    first, groupMappings, assessmentsByControl, testsByControl, asOfDate, query.freshnessWindowDays());

            // Apply domain filter if requested
            if (query.domain() != null) {
                boolean matchesDomain =
                        item.domainAttributions().stream().anyMatch(da -> da.domain() == query.domain());
                if (!matchesDomain) {
                    continue;
                }
            }

            items.add(item);
            if (!item.limitations().isEmpty()) {
                withLimitations++;
            }
            // Count each distinct domain this control is attributed to. A control mapped into
            // multiple FAIR-CAM domains contributes once to each bucket, so byDomain reflects
            // domain membership rather than an arbitrary first-mapping pick.
            item.domainAttributions().stream()
                    .map(da -> da.domain().jsonKey())
                    .distinct()
                    .forEach(domainKey -> byDomainCounts.merge(domainKey, 1, Integer::sum));
        }

        var counts = new FairCamControlAnalyticsResult.Counts(items.size(), byDomainCounts, withLimitations);
        return new FairCamControlAnalyticsResult(
                ANALYSIS_KIND, projectIdentifier, effectiveAsOf, DERIVATION_METHOD, items, counts, List.of());
    }

    private String resolveProjectIdentifier(UUID projectId) {
        return projectRepository
                .findById(projectId)
                .map(Project::getIdentifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }

    private List<RiskControlMapping> loadMappings(UUID projectId, FairCamControlAnalyticsQuery q) {
        // Pick the most selective single-column query as the candidate set, then apply every
        // supplied filter in memory so multiple filters intersect (composable) rather than
        // precedence-select. A request with controlId AND riskScenarioId must return only the
        // mappings of that control to that scenario, not every mapping of the control.
        return selectCandidateMappings(projectId, q).stream()
                .filter(m -> matchesAllFilters(m, q))
                .toList();
    }

    private List<RiskControlMapping> selectCandidateMappings(UUID projectId, FairCamControlAnalyticsQuery q) {
        if (q.controlId() != null) {
            return mappingRepo.findByProjectIdAndControlId(projectId, q.controlId());
        }
        if (q.scopedImplementationId() != null) {
            return mappingRepo.findByProjectIdAndScopedImplementationId(projectId, q.scopedImplementationId());
        }
        if (q.riskScenarioId() != null) {
            return mappingRepo.findByProjectIdAndRiskScenarioId(projectId, q.riskScenarioId());
        }
        if (q.riskRegisterRecordId() != null) {
            return mappingRepo.findByProjectIdAndRiskRegisterRecordId(projectId, q.riskRegisterRecordId());
        }
        if (q.threatModelId() != null) {
            return mappingRepo.findByProjectIdAndThreatModelId(projectId, q.threatModelId());
        }
        return mappingRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    private static boolean matchesAllFilters(RiskControlMapping m, FairCamControlAnalyticsQuery q) {
        return matchesId(q.controlId(), m.isControlSide() ? m.getControl().getId() : null)
                && matchesId(
                        q.scopedImplementationId(),
                        m.isScopedImplementationSide()
                                ? m.getScopedImplementation().getId()
                                : null)
                && matchesId(
                        q.riskScenarioId(),
                        m.isScenarioSide() ? m.getRiskScenario().getId() : null)
                && matchesId(
                        q.riskRegisterRecordId(),
                        m.isRegisterRecordSide() ? m.getRiskRegisterRecord().getId() : null)
                && matchesId(
                        q.threatModelId(), m.isThreatSide() ? m.getThreatModel().getId() : null)
                && matchesId(
                        q.methodologyProfileId(),
                        m.getMethodologyProfile() == null
                                ? null
                                : m.getMethodologyProfile().getId());
    }

    /** A filter matches when it is unset, or set and equal to the mapping's corresponding id. */
    private static boolean matchesId(UUID filterId, UUID actualId) {
        return filterId == null || filterId.equals(actualId);
    }

    private FairCamControlAnalyticsResult.ControlAnalyticsItem buildItem(
            RiskControlMapping first,
            List<RiskControlMapping> groupMappings,
            Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl,
            Map<UUID, List<ControlTest>> testsByControl,
            LocalDate asOfDate,
            int freshnessWindowDays) {

        // Determine control-side info
        String endpointType;
        UUID endpointId;
        UUID itemControlId;
        UUID itemScopedImplementationId;
        String controlUid;
        String controlName;
        UUID underlyingControlId;

        if (first.isControlSide()) {
            endpointType = "CONTROL";
            endpointId = first.getControl().getId();
            itemControlId = first.getControl().getId();
            itemScopedImplementationId = null;
            controlUid = first.getControl().getUid();
            controlName = first.getControl().getTitle();
            underlyingControlId = first.getControl().getId();
        } else {
            endpointType = "SCOPED_IMPLEMENTATION";
            endpointId = first.getScopedImplementation().getId();
            itemControlId = null;
            itemScopedImplementationId = first.getScopedImplementation().getId();
            controlUid = first.getScopedImplementation().getUid();
            controlName = first.getScopedImplementation().getControl().getTitle();
            underlyingControlId = first.getScopedImplementation().getControl().getId();
        }

        List<String> limitations = new ArrayList<>();

        // Domain attribution and effects are contextual to each mapping, so derive them across
        // EVERY mapping in this control-endpoint group, not just the first. A control mapped to
        // two scenarios with different fair_cam_domain or effect dimensions surfaces both.
        List<FairCamControlAnalyticsResult.DomainAttribution> domainAttributions =
                deriveDomainAttributions(groupMappings, limitations);

        // Capability from latest design effectiveness as-of
        FairCamControlAnalyticsResult.Measurement capability =
                deriveCapability(underlyingControlId, assessmentsByControl, limitations);

        // Coverage = count of distinct analysis endpoints this control maps to
        FairCamControlAnalyticsResult.Measurement coverage = deriveCoverage(groupMappings, limitations);

        // Operational performance from latest operating effectiveness + fresh PASS tests
        FairCamControlAnalyticsResult.Measurement operationalPerformance = deriveOperationalPerformance(
                underlyingControlId, assessmentsByControl, testsByControl, asOfDate, freshnessWindowDays, limitations);

        // Effects from each mapping's methodology influence dimension keys
        List<FairCamControlAnalyticsResult.EffectEntry> effects = deriveEffects(groupMappings, limitations);

        // Evidence refs from all mappings in this group
        List<String> evidenceRefs = groupMappings.stream()
                .flatMap(m -> m.getEvidenceRefs().stream())
                .map(ref -> ref.getEvidenceRef())
                .distinct()
                .toList();

        return new FairCamControlAnalyticsResult.ControlAnalyticsItem(
                endpointType,
                endpointId,
                itemControlId,
                itemScopedImplementationId,
                controlUid,
                controlName,
                domainAttributions,
                capability,
                coverage,
                operationalPerformance,
                effects,
                evidenceRefs,
                List.copyOf(limitations));
    }

    private List<FairCamControlAnalyticsResult.DomainAttribution> deriveDomainAttributions(
            List<RiskControlMapping> groupMappings, List<String> limitations) {
        List<FairCamControlAnalyticsResult.DomainAttribution> attributions = new ArrayList<>();
        for (RiskControlMapping m : groupMappings) {
            FairCamControlAnalyticsResult.DomainAttribution attribution = deriveDomainAttribution(m, limitations);
            if (attribution != null && !attributions.contains(attribution)) {
                attributions.add(attribution);
            }
        }
        if (attributions.isEmpty()) {
            addOnce(
                    limitations,
                    "domain not attributable: no fair_cam_domain in methodology_influence and no mappable control role");
        }
        return attributions;
    }

    /** Derives one FAIR-CAM domain attribution for a single mapping, tagged with its analysis endpoint. */
    private FairCamControlAnalyticsResult.DomainAttribution deriveDomainAttribution(
            RiskControlMapping mapping, List<String> limitations) {
        String endpoint = analysisEndpointRef(mapping);
        Map<String, Object> influence = asMap(mapping.getMethodologyInfluence());
        // PRIMARY: look for fair_cam_domain key in methodologyInfluence
        if (influence != null && influence.containsKey(KEY_FAIR_CAM_DOMAIN)) {
            Object rawDomain = influence.get(KEY_FAIR_CAM_DOMAIN);
            FairCamControlDomain domain =
                    FairCamControlDomain.fromJsonKey(rawDomain == null ? null : String.valueOf(rawDomain));
            if (domain != null) {
                return new FairCamControlAnalyticsResult.DomainAttribution(domain, "methodology_influence", endpoint);
            }
        }
        // FALLBACK: classify from MappingControlRole
        MappingControlRole role = mapping.getControlRole();
        FairCamControlDomain roleDomain = classifyFromRole(role);
        if (roleDomain != null) {
            addOnce(
                    limitations,
                    "domain attributed from mapping_control_role (" + role.name()
                            + "); set fair_cam_domain in methodology_influence for methodology-defined attribution");
            return new FairCamControlAnalyticsResult.DomainAttribution(roleDomain, "mapping_control_role", endpoint);
        }
        return null;
    }

    private FairCamControlDomain classifyFromRole(MappingControlRole role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case PREVENTIVE, DETECTIVE, DETERRENT -> FairCamControlDomain.LOSS_EVENT_CONTROL;
            case CORRECTIVE, RECOVERY, COMPENSATING -> FairCamControlDomain.VARIANCE_MANAGEMENT_CONTROL;
            case DIRECTIVE -> FairCamControlDomain.DECISION_SUPPORT_CONTROL;
        };
    }

    private FairCamControlAnalyticsResult.Measurement deriveCapability(
            UUID controlId,
            Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl,
            List<String> limitations) {
        List<ControlEffectivenessAssessment> assessments = assessmentsByControl.getOrDefault(controlId, List.of());
        if (assessments.isEmpty()) {
            limitations.add("capability not derivable: no control effectiveness assessment as-of");
            return new FairCamControlAnalyticsResult.Measurement(
                    SCALE_ORDINAL, UNITS_RATING, null, NOT_DERIVABLE_NO_ASSESSMENT);
        }
        // assessments are ordered by assessedAt desc (from the repo query), so first is latest
        ControlEffectivenessAssessment latest = assessments.get(0);
        return new FairCamControlAnalyticsResult.Measurement(
                SCALE_ORDINAL,
                UNITS_RATING,
                latest.getDesignEffectiveness().name(),
                "latest design_effectiveness assessment as-of");
    }

    private FairCamControlAnalyticsResult.Measurement deriveCoverage(
            List<RiskControlMapping> groupMappings, List<String> limitations) {
        // Count distinct analysis endpoints
        long distinctEndpoints = groupMappings.stream()
                .map(FairCamControlAnalyticsService::analysisEndpointRef)
                .filter(k -> k != null)
                .distinct()
                .count();
        if (distinctEndpoints == 0) {
            limitations.add("coverage is 0: control has no analysis endpoint mappings");
        }
        return new FairCamControlAnalyticsResult.Measurement(
                "count", "endpoints", (int) distinctEndpoints, "distinct analysis endpoints mapped");
    }

    private FairCamControlAnalyticsResult.Measurement deriveOperationalPerformance(
            UUID controlId,
            Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl,
            Map<UUID, List<ControlTest>> testsByControl,
            LocalDate asOfDate,
            int freshnessWindowDays,
            List<String> limitations) {
        List<ControlEffectivenessAssessment> assessments = assessmentsByControl.getOrDefault(controlId, List.of());
        if (assessments.isEmpty()) {
            limitations.add("operational_performance not derivable: no control effectiveness assessment as-of");
            return new FairCamControlAnalyticsResult.Measurement(
                    SCALE_ORDINAL, UNITS_RATING, null, NOT_DERIVABLE_NO_ASSESSMENT);
        }
        ControlEffectivenessAssessment latest = assessments.get(0);

        // Count fresh PASS tests within freshnessWindowDays of asOf
        LocalDate freshnessFloor = asOfDate.minusDays(freshnessWindowDays);
        List<ControlTest> tests = testsByControl.getOrDefault(controlId, List.of());
        long freshPassCount = tests.stream()
                .filter(t -> t.getConclusion() == ControlTestConclusion.EFFECTIVE)
                .filter(t -> !t.getTestDate().isBefore(freshnessFloor))
                .count();

        if (freshPassCount == 0) {
            limitations.add(
                    "operational_performance: no fresh PASS tests within " + freshnessWindowDays + " days of as-of");
        }

        String basis = "latest operating_effectiveness as-of; " + freshPassCount + " fresh PASS test(s) within "
                + freshnessWindowDays + " days";
        return new FairCamControlAnalyticsResult.Measurement(
                SCALE_ORDINAL, UNITS_RATING, latest.getOperatingEffectiveness().name(), basis);
    }

    private List<FairCamControlAnalyticsResult.EffectEntry> deriveEffects(
            List<RiskControlMapping> groupMappings, List<String> limitations) {
        List<FairCamControlAnalyticsResult.EffectEntry> effects = new ArrayList<>();
        boolean anyInfluence = false;
        for (RiskControlMapping m : groupMappings) {
            Map<String, Object> influence = asMap(m.getMethodologyInfluence());
            if (influence == null) {
                continue;
            }
            anyInfluence = true;
            String endpoint = analysisEndpointRef(m);
            for (FairCamEffectDimension dim : FairCamEffectDimension.values()) {
                if (influence.containsKey(dim.jsonKey())) {
                    effects.add(
                            new FairCamControlAnalyticsResult.EffectEntry(dim, influence.get(dim.jsonKey()), endpoint));
                }
            }
        }
        if (!anyInfluence) {
            addOnce(limitations, "effects not derivable: no methodology_influence on any mapping");
        } else if (effects.isEmpty()) {
            addOnce(limitations, "effects not derivable: no FAIR-CAM dimension keys in methodology_influence");
        }
        return effects;
    }

    /**
     * Stable reference string for a mapping's analysis endpoint, or {@code null} when the mapping
     * has no analysis-side endpoint. Shared by coverage counting, domain attribution, and effect
     * attribution so the same endpoint identity is used everywhere.
     */
    private static String analysisEndpointRef(RiskControlMapping m) {
        if (m.isScenarioSide()) {
            return "RISK_SCENARIO:" + m.getRiskScenario().getId();
        }
        if (m.isRegisterRecordSide()) {
            return "RISK_REGISTER_RECORD:" + m.getRiskRegisterRecord().getId();
        }
        if (m.isThreatSide()) {
            return "THREAT_MODEL:" + m.getThreatModel().getId();
        }
        return null;
    }

    /** Appends a limitation only when an identical message is not already present (per-control dedup). */
    private static void addOnce(List<String> limitations, String message) {
        if (!limitations.contains(message)) {
            limitations.add(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? Collections.unmodifiableMap((Map<String, Object>) m) : null;
    }
}
