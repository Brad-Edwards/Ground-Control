package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T008 top-N risk scenarios projection. Reads the latest
 * {@link RiskAssessmentResult} per scenario for the project and ranks them
 * either by methodology-specific risk level
 * ({@link RiskTopNOrderBy#CURRENT_ASSESSMENT_OUTPUT}) or by recency of
 * assessment ({@link RiskTopNOrderBy#ASSESSMENT_AT_DESC}).
 *
 * <p>When mixing methodology families in the same N, the envelope emits a
 * limitation: ordinal NIST risk levels are not directly comparable with FAIR
 * monetary loss bands without a methodology-specific conversion rule per
 * ADR-035.
 */
@Service
@Transactional(readOnly = true)
public class RiskTopNService {

    static final String ANALYSIS_KIND = "risk_top_n";
    static final String DERIVATION_METHOD = "latest-per-scenario-top-n-v1";
    static final String SCALE = "methodology-specific";
    static final String UNITS = "methodology-specific";
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_LIMIT = 10;
    static final String MIXED_METHODOLOGY_LIMITATION =
            "top-N spans multiple methodology families (%s); their ranking values are not directly"
                    + " comparable without an explicit conversion rule (ADR-035 methodology attribution)";
    static final String FAIR_RANKING_LIMITATION =
            "FAIR methodology rows do not produce a qualitative risk_level; ranking by"
                    + " current_assessment_output is not meaningful (ADR-035)";
    static final String CONSIDERED_BUT_FILTERED_LIMITATION = "rows with no ranking value were excluded";

    private static final Map<String, Integer> RISK_LEVEL_ORDER = Map.of(
            "VERY_LOW", 1,
            "LOW", 2,
            "MODERATE", 3,
            "MEDIUM", 3,
            "HIGH", 4,
            "VERY_HIGH", 5,
            "CRITICAL", 5);

    private final RiskAssessmentResultRepository repository;
    private final ProjectRepository projectRepository;

    public RiskTopNService(RiskAssessmentResultRepository repository, ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    public RiskTopNResult topN(UUID projectId, Instant asOf, int requestedLimit, RiskTopNOrderBy orderBy) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(orderBy, "orderBy");
        if (requestedLimit <= 0 || requestedLimit > MAX_LIMIT) {
            throw new DomainValidationException(
                    "limit must be between 1 and " + MAX_LIMIT,
                    "validation_error",
                    Map.of("parameter", "limit", "value", requestedLimit));
        }
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();

        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        List<RiskAssessmentResult> rows = repository.findLatestPerScenarioByProjectId(projectId);

        List<Ranked> ranked = new ArrayList<>();
        List<String> projectLimitations = new ArrayList<>();
        boolean droppedForMissingValue = false;

        for (RiskAssessmentResult row : rows) {
            Ranked candidate = score(row, orderBy);
            if (candidate == null) {
                droppedForMissingValue = true;
                continue;
            }
            ranked.add(candidate);
        }

        if (orderBy == RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT) {
            ranked.sort(Comparator.comparingInt(Ranked::score)
                    .reversed()
                    .thenComparing(Ranked::tieBreakerInstant, Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            ranked.sort(
                    Comparator.comparing(Ranked::tieBreakerInstant, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        List<RiskTopNResult.TopNEntry> entries = new ArrayList<>();
        int rank = 1;
        // Compute methodology-family attribution from the rows that actually appear in
        // the returned top-N, NOT from every considered row. Otherwise a row dropped
        // for missing risk_level (FAIR rows under CURRENT_ASSESSMENT_OUTPUT, etc.) would
        // pollute the mixed-methodology limitation even though it never made the output.
        Set<String> families = new HashSet<>();
        for (Ranked r : ranked) {
            if (entries.size() >= requestedLimit) {
                break;
            }
            MethodologyProfile profile = r.row().getMethodologyProfile();
            if (profile != null) {
                families.add(profile.getFamily().name());
            }
            entries.add(toEntry(rank++, r, orderBy));
        }

        if (families.size() > 1) {
            List<String> sortedFamilies = families.stream().sorted().toList();
            projectLimitations.add(String.format(MIXED_METHODOLOGY_LIMITATION, String.join(", ", sortedFamilies)));
        }
        if (orderBy == RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT && families.contains(MethodologyFamily.FAIR.name())) {
            projectLimitations.add(FAIR_RANKING_LIMITATION);
        }
        if (droppedForMissingValue) {
            projectLimitations.add(CONSIDERED_BUT_FILTERED_LIMITATION);
        }

        return new RiskTopNResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                new RiskTopNResult.Inputs(project.getIdentifier(), effectiveAsOf, requestedLimit, orderBy.name()),
                entries,
                new RiskTopNResult.Counts(rows.size(), entries.size()),
                projectLimitations);
    }

    private Ranked score(RiskAssessmentResult row, RiskTopNOrderBy orderBy) {
        return switch (orderBy) {
            case CURRENT_ASSESSMENT_OUTPUT -> scoreRiskLevel(row);
            case ASSESSMENT_AT_DESC -> new Ranked(
                    row, 0, row.getAssessmentAt() != null ? row.getAssessmentAt() : row.getCreatedAt(), null);
        };
    }

    private Ranked scoreRiskLevel(RiskAssessmentResult row) {
        String level = null;
        Map<String, Object> computed = row.getComputedOutputs();
        if (computed != null) {
            Object raw = computed.get("risk_level");
            if (raw != null) {
                level = raw.toString();
            }
        }
        if (level == null) {
            NistLikelihoodBand likelihood = readLikelihood(row);
            if (likelihood != null) {
                level = likelihood.name();
            }
        }
        if (level == null) {
            return null;
        }
        Integer score = RISK_LEVEL_ORDER.get(level.trim().toUpperCase());
        if (score == null) {
            return null;
        }
        return new Ranked(
                row, score, row.getAssessmentAt() != null ? row.getAssessmentAt() : row.getCreatedAt(), level);
    }

    private NistLikelihoodBand readLikelihood(RiskAssessmentResult row) {
        Map<String, Object> computed = row.getComputedOutputs();
        if (computed != null) {
            Object raw = computed.get("overall_likelihood");
            if (raw != null) {
                try {
                    return NistLikelihoodBand.valueOf(raw.toString().trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private RiskTopNResult.TopNEntry toEntry(int rank, Ranked r, RiskTopNOrderBy orderBy) {
        RiskAssessmentResult row = r.row();
        MethodologyProfile profile = row.getMethodologyProfile();
        String metric = orderBy == RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT ? "risk_level" : "assessment_at";
        String value = orderBy == RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT
                ? r.rankingLabel()
                : r.tieBreakerInstant() != null ? r.tieBreakerInstant().toString() : null;
        List<String> perRowLimitations = new ArrayList<>();
        if (orderBy == RiskTopNOrderBy.CURRENT_ASSESSMENT_OUTPUT
                && profile != null
                && profile.getFamily() == MethodologyFamily.FAIR) {
            perRowLimitations.add(FAIR_RANKING_LIMITATION);
        }
        return new RiskTopNResult.TopNEntry(
                rank,
                row.getId(),
                row.getRiskScenario() != null ? row.getRiskScenario().getId() : null,
                row.getRiskScenario() != null ? row.getRiskScenario().getUid() : null,
                row.getRiskScenario() != null ? row.getRiskScenario().getTitle() : null,
                profile != null ? profile.getId() : null,
                profile != null ? profile.getFamily().name() : null,
                metric,
                value,
                row.getApprovalState() != null ? row.getApprovalState().name() : null,
                row.getAssessmentAt(),
                List.copyOf(perRowLimitations));
    }

    private record Ranked(RiskAssessmentResult row, int score, Instant tieBreakerInstant, String rankingLabel) {}
}
