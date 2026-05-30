package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T008 qualitative risk heat map. Projects every project-scoped
 * {@link RiskAssessmentResult} onto an ordinal likelihood × impact grid using
 * the NIST band vocabulary (also valid for ISO_27005 ordinal profiles).
 *
 * <p>Quantitative methodologies (FAIR) are not plotted; per-row contribution
 * is dropped and a project-level limitation is emitted recording the
 * incompatibility. The envelope carries the {@code methodologyProfileId} of
 * the requested profile (when supplied) so consumers know which methodology
 * gated the plot.
 */
@Service
@Transactional(readOnly = true)
public class RiskHeatmapService {

    static final String ANALYSIS_KIND = "risk_heatmap";
    static final String DERIVATION_METHOD = "qualitative-likelihood-impact-heatmap-v1";
    static final String SCALE = "ordinal";
    static final String UNITS = "qualitative ordinal levels";
    static final String FAIR_INCOMPATIBILITY_LIMITATION =
            "FAIR methodology rows are quantitative (loss distributions); they cannot be plotted on an"
                    + " ordinal likelihood × impact heat map without a methodology-specific conversion rule"
                    + " (ADR-035: do not collapse FAIR dollars into qualitative bands)";
    static final String INCOMPATIBLE_PROFILE_LIMITATION =
            "requested methodology profile does not produce ordinal likelihood × impact bands;"
                    + " no rows were plotted (ADR-035 methodology attribution)";

    private static final String KEY_LIKELIHOOD_OVERALL = "likelihood_overall";
    private static final String KEY_IMPACT_LEVEL = "impact_level";
    private static final String OUT_OVERALL_LIKELIHOOD = "overall_likelihood";
    private static final String OUT_IMPACT_LEVEL = "impact_level";

    private final RiskAssessmentResultRepository repository;
    private final ProjectRepository projectRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;

    public RiskHeatmapService(
            RiskAssessmentResultRepository repository,
            ProjectRepository projectRepository,
            MethodologyProfileRepository methodologyProfileRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
    }

    public RiskHeatmapResult buildHeatmap(UUID projectId, Instant asOf, UUID methodologyProfileId) {
        Objects.requireNonNull(projectId, "projectId");
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        // Latest-per-scenario semantics make every plotted point the current
        // assessment, in line with the GC-T008 "current assessment output" wording.
        List<RiskAssessmentResult> rows = repository.findLatestPerScenarioByProjectId(projectId);

        Map<CellKey, List<UUID>> cellPlotIds = new LinkedHashMap<>();
        Map<String, Integer> byFamily = new TreeMap<>();
        int plotted = 0;
        int incompatible = 0;
        List<String> limitations = new ArrayList<>();
        boolean profileRestricted = methodologyProfileId != null;

        for (RiskAssessmentResult row : rows) {
            MethodologyProfile profile = row.getMethodologyProfile();
            if (profile == null) {
                incompatible++;
                continue;
            }
            // Apply the methodology-profile filter BEFORE counting byFamily / triggering
            // the FAIR limitation, so a caller restricted to a non-FAIR profile never
            // sees a FAIR-incompatibility limitation referencing rows they never asked
            // about (ADR-035 methodology attribution).
            if (profileRestricted && !profile.getId().equals(methodologyProfileId)) {
                continue;
            }
            byFamily.merge(profile.getFamily().name(), 1, Integer::sum);

            if (!supportsHeatmap(profile.getFamily())) {
                incompatible++;
                continue;
            }

            NistLikelihoodBand likelihood = resolveLikelihood(row);
            NistImpactBand impact = resolveImpact(row);
            if (likelihood == null || impact == null) {
                incompatible++;
                continue;
            }

            CellKey key = new CellKey(likelihood, impact);
            cellPlotIds.computeIfAbsent(key, k -> new ArrayList<>()).add(row.getId());
            plotted++;
        }

        boolean anyFairRow = byFamily.getOrDefault(MethodologyFamily.FAIR.name(), 0) > 0;
        if (anyFairRow) {
            limitations.add(FAIR_INCOMPATIBILITY_LIMITATION);
        }
        if (profileRestricted && plotted == 0 && incompatible > 0) {
            limitations.add(INCOMPATIBLE_PROFILE_LIMITATION);
        }

        // Resolve the requested profile via the repository (scoped to the project)
        // so the envelope always carries methodology_profile_id / family of the
        // REQUESTED profile per ADR-035, even when zero rows match it.
        MethodologyProfile resolvedProfile = null;
        if (profileRestricted) {
            resolvedProfile = methodologyProfileRepository
                    .findByIdAndProjectId(methodologyProfileId, projectId)
                    .orElseGet(() -> resolveProfile(rows, methodologyProfileId));
        }

        List<RiskHeatmapResult.HeatmapCell> cells = new ArrayList<>();
        for (Map.Entry<CellKey, List<UUID>> entry : cellPlotIds.entrySet()) {
            CellKey k = entry.getKey();
            cells.add(new RiskHeatmapResult.HeatmapCell(
                    k.likelihood.ordinal() + 1,
                    k.likelihood.name(),
                    k.impact.ordinal() + 1,
                    k.impact.name(),
                    entry.getValue().size(),
                    List.copyOf(entry.getValue())));
        }

        // ADR-035 attribution: when the caller supplied a methodologyProfileId we MUST
        // carry it back even when no row matched. methodologyFamily reflects what we
        // could resolve from the profile lookup; it is null only when neither the
        // repository nor the live row-set knows the profile.
        UUID envelopeProfileId =
                profileRestricted ? methodologyProfileId : (resolvedProfile != null ? resolvedProfile.getId() : null);
        String envelopeFamily =
                resolvedProfile != null ? resolvedProfile.getFamily().name() : null;

        return new RiskHeatmapResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                envelopeProfileId,
                envelopeFamily,
                SCALE,
                UNITS,
                new RiskHeatmapResult.Inputs(project.getIdentifier(), effectiveAsOf, methodologyProfileId),
                cells,
                new RiskHeatmapResult.Counts(rows.size(), plotted, incompatible, byFamily),
                limitations);
    }

    private boolean supportsHeatmap(MethodologyFamily family) {
        return family == MethodologyFamily.NIST_SP800_30_R1
                || family == MethodologyFamily.ISO_27005
                || family == MethodologyFamily.CUSTOM;
    }

    private NistLikelihoodBand resolveLikelihood(RiskAssessmentResult row) {
        NistLikelihoodBand persisted = parseLikelihood(getString(row.getComputedOutputs(), OUT_OVERALL_LIKELIHOOD));
        if (persisted != null) {
            return persisted;
        }
        return parseLikelihood(getString(row.getInputFactors(), KEY_LIKELIHOOD_OVERALL));
    }

    private NistImpactBand resolveImpact(RiskAssessmentResult row) {
        NistImpactBand persisted = parseImpact(getString(row.getComputedOutputs(), OUT_IMPACT_LEVEL));
        if (persisted != null) {
            return persisted;
        }
        return parseImpact(getString(row.getInputFactors(), KEY_IMPACT_LEVEL));
    }

    private static NistLikelihoodBand parseLikelihood(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NistLikelihoodBand.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static NistImpactBand parseImpact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return NistImpactBand.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static MethodologyProfile resolveProfile(List<RiskAssessmentResult> rows, UUID profileId) {
        for (RiskAssessmentResult row : rows) {
            MethodologyProfile profile = row.getMethodologyProfile();
            if (profile != null && profileId.equals(profile.getId())) {
                return profile;
            }
        }
        return null;
    }

    private record CellKey(NistLikelihoodBand likelihood, NistImpactBand impact) {}
}
