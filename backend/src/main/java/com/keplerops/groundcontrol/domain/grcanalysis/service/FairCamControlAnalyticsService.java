package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAIR-CAM control analytics per GC-I017.
 *
 * <p>Reads {@link ControlEffectivenessAssessment} rows for the project (or for a
 * single control when filtered) and emits a per-control FAIR-CAM attribution:
 * which FAIR-CAM domain the control sits in, plus the three measurement
 * dimensions (capability, coverage, operational performance). Each dimension
 * has its own scale/units so the analysis layer never collapses them into a
 * single effectiveness score.
 *
 * <p>The service is read-only — it never mutates any control or assessment
 * row. {@link ControlEffectivenessAssessment#getOperatingEffectiveness()} is
 * reported beside the FAIR-CAM dimensions rather than absorbing them; FAIR-CAM
 * is intentionally orthogonal to the GC-I013 effectiveness rating.
 *
 * <p>When an analyzed assessment lacks a
 * {@link FairCamControlDomain} attribution, a per-item limitation is emitted
 * (per the GC-L007 preflight result contract — the response surfaces the gap
 * rather than synthesizing a default domain). When a project has no
 * {@code ControlTest} / {@code ControlEffectivenessAssessment} evidence at all,
 * the top-level {@code limitations} array carries that fact.
 */
@Service
@Transactional(readOnly = true)
public class FairCamControlAnalyticsService {

    static final String ANALYSIS_KIND = "fair_cam_control_analytics";
    static final String DERIVATION_METHOD = "fair-cam-v1-domain-attribution-and-three-dimensions";
    static final String SCALE_FRACTION = "fraction";
    static final String UNITS_FRACTION = "fraction (0.0–1.0) per FAIR-CAM dimension";

    private static final Map<ControlEffectivenessRating, Double> CAPABILITY_BY_RATING = Map.of(
            ControlEffectivenessRating.EFFECTIVE, 1.0,
            ControlEffectivenessRating.PARTIALLY_EFFECTIVE, 0.5,
            ControlEffectivenessRating.INEFFECTIVE, 0.0);
    private static final Map<ControlEffectivenessRating, Double> OPERATIONAL_BY_RATING = Map.of(
            ControlEffectivenessRating.EFFECTIVE, 1.0,
            ControlEffectivenessRating.PARTIALLY_EFFECTIVE, 0.5,
            ControlEffectivenessRating.INEFFECTIVE, 0.0);

    private final ControlEffectivenessAssessmentRepository repository;
    private final ProjectRepository projectRepository;

    public FairCamControlAnalyticsService(
            ControlEffectivenessAssessmentRepository repository, ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    public FairCamControlAnalyticsResult analyze(UUID projectId, Instant asOf, UUID controlId) {
        Instant effectiveAsOf = asOf == null ? Instant.now() : asOf;
        String projectIdentifier = projectRepository
                .findById(projectId)
                .map(Project::getIdentifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        LocalDate asOfDate = LocalDate.ofInstant(effectiveAsOf, ZoneOffset.UTC);
        List<ControlEffectivenessAssessment> rows = loadRows(projectId, asOfDate, controlId);
        List<String> topLimitations = new ArrayList<>();
        if (rows.isEmpty()) {
            topLimitations.add(
                    "no ControlEffectivenessAssessment evidence for the requested scope at " + effectiveAsOf);
        }

        List<FairCamControlAnalyticsResult.ControlAnalyticsItem> items = new ArrayList<>();
        Map<String, Integer> byDomain = new LinkedHashMap<>();
        int withLimitations = 0;
        for (ControlEffectivenessAssessment row : rows) {
            var item = toItem(row);
            items.add(item);
            String domainKey = item.fairCamControlDomain() == null
                    ? "UNATTRIBUTED"
                    : item.fairCamControlDomain().name();
            byDomain.merge(domainKey, 1, Integer::sum);
            if (!item.limitations().isEmpty()) {
                withLimitations++;
            }
        }

        var counts = new FairCamControlAnalyticsResult.Counts(items.size(), byDomain, withLimitations);
        return new FairCamControlAnalyticsResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE_FRACTION,
                UNITS_FRACTION,
                items,
                counts,
                List.copyOf(topLimitations));
    }

    private List<ControlEffectivenessAssessment> loadRows(UUID projectId, LocalDate asOfDate, UUID controlId) {
        if (controlId != null) {
            // Bound to a single control: take the project-scoped slice and
            // filter to the requested control + cut-off date.
            return repository.findByProjectIdAndControlIdOrderByAssessedAtDesc(projectId, controlId).stream()
                    .filter(a -> !a.getAssessedAt().isAfter(asOfDate))
                    .toList();
        }
        return repository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                projectId, asOfDate);
    }

    private FairCamControlAnalyticsResult.ControlAnalyticsItem toItem(ControlEffectivenessAssessment row) {
        List<String> limitations = new ArrayList<>();

        FairCamControlDomain domain = row.getFairCamControlDomain();
        if (domain == null) {
            limitations.add("FAIR-CAM control_domain not attributed on assessment " + row.getUid()
                    + "; analytics emit dimensions but cannot place this control in a FAIR-CAM domain");
        }

        Control control = row.getControl();
        double capabilityValue = CAPABILITY_BY_RATING.getOrDefault(row.getDesignEffectiveness(), 0.0);
        double operationalValue = OPERATIONAL_BY_RATING.getOrDefault(row.getOperatingEffectiveness(), 0.0);
        boolean hasSupportingTests = row.getSupportingTestIds() != null
                && !row.getSupportingTestIds().isEmpty();
        double coverageValue = hasSupportingTests ? 1.0 : 0.0;
        if (!hasSupportingTests) {
            limitations.add("no supporting ControlTest evidence — FAIR-CAM coverage defaulted to 0.0 and operational"
                    + " performance not corroborated");
        }

        var capability = new FairCamControlAnalyticsResult.DimensionMeasurement(
                capabilityValue,
                SCALE_FRACTION,
                UNITS_FRACTION,
                "derived from design_effectiveness via FAIR-CAM capability mapping v1");
        var operationalPerformance = new FairCamControlAnalyticsResult.DimensionMeasurement(
                operationalValue,
                SCALE_FRACTION,
                UNITS_FRACTION,
                "derived from operating_effectiveness via FAIR-CAM operational-performance mapping v1");
        var coverage = new FairCamControlAnalyticsResult.DimensionMeasurement(
                coverageValue,
                SCALE_FRACTION,
                UNITS_FRACTION,
                "presence of supporting ControlTest evidence (binary 0/1 in v1)");

        var dims = new FairCamControlAnalyticsResult.Dimensions(capability, coverage, operationalPerformance);

        return new FairCamControlAnalyticsResult.ControlAnalyticsItem(
                row.getId(),
                control == null ? null : control.getId(),
                control == null ? null : control.getUid(),
                control == null ? null : control.getTitle(),
                domain,
                row.getAssessedAt(),
                row.getAssessor(),
                row.getDesignEffectiveness(),
                row.getOperatingEffectiveness(),
                dims,
                row.getSupportingTestIds() == null ? List.of() : List.copyOf(row.getSupportingTestIds()),
                List.copyOf(limitations));
    }
}
