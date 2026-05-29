package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistImpactBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NistLikelihoodBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatEventKind;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ThreatSourceRelevance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NIST SP 800-30 Rev. 1 risk-assessment view per GC-T014. Reads existing
 * {@link RiskAssessmentResult} rows whose {@link MethodologyProfile} family is
 * {@link MethodologyFamily#NIST_SP800_30_R1}, decodes the methodology-defined
 * input map, derives overall likelihood and qualitative risk level per NIST
 * Table G-5 / Table I-2 when not analyst-supplied, and returns a
 * methodology-attributed envelope.
 *
 * <p>The service is read-only — it never mutates {@code RiskAssessmentResult}.
 * Callers who want to persist the computed outputs route through
 * {@code RiskAssessmentResultController} POST/PUT, which writes through
 * {@code RiskAssessmentResultService} into the same {@code computedOutputs}
 * map.
 */
@Service
@Transactional(readOnly = true)
public class NistAssessmentService {

    static final String ANALYSIS_KIND = "nist_assessment";
    static final String DERIVATION_METHOD = "nist-sp800-30-rev1-5x5-matrix-v1";
    static final String SCALE = "ordinal";
    static final String UNITS = "qualitative ordinal levels";
    static final String MATRIX_CONVERSION_RULE =
            "overall_likelihood × impact_level → risk_level per NIST SP 800-30 Rev. 1 Table I-2";

    private final RiskAssessmentResultRepository repository;
    private final ProjectRepository projectRepository;

    public NistAssessmentService(RiskAssessmentResultRepository repository, ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    public NistAssessmentResult analyze(UUID projectId, Instant asOf, UUID assessmentId, UUID riskScenarioId) {
        Instant effectiveAsOf = asOf == null ? Instant.now() : asOf;
        String projectIdentifier = projectRepository
                .findById(projectId)
                .map(p -> p.getIdentifier())
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        List<RiskAssessmentResult> rows = loadRows(projectId, assessmentId, riskScenarioId);

        List<NistAssessmentResult.NistAssessmentItem> items = new ArrayList<>();
        Map<String, Integer> byRiskLevel = new LinkedHashMap<>();
        int withLimitations = 0;
        for (RiskAssessmentResult row : rows) {
            if (row.getMethodologyProfile() == null
                    || row.getMethodologyProfile().getFamily() != MethodologyFamily.NIST_SP800_30_R1) {
                continue;
            }
            NistAssessmentResult.NistAssessmentItem item = toItem(row);
            items.add(item);
            byRiskLevel.merge(item.outputs().riskLevel(), 1, Integer::sum);
            if (!item.limitations().isEmpty()) {
                withLimitations++;
            }
        }

        var counts = new NistAssessmentResult.Counts(items.size(), byRiskLevel, withLimitations);
        return new NistAssessmentResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                MATRIX_CONVERSION_RULE,
                items,
                counts,
                List.of());
    }

    private List<RiskAssessmentResult> loadRows(UUID projectId, UUID assessmentId, UUID riskScenarioId) {
        if (assessmentId != null) {
            RiskAssessmentResult row = repository
                    .findByIdAndProjectIdWithObservations(assessmentId, projectId)
                    .orElseThrow(() -> new NotFoundException("Risk assessment result not found: " + assessmentId));
            MethodologyProfile profile = row.getMethodologyProfile();
            if (profile == null || profile.getFamily() != MethodologyFamily.NIST_SP800_30_R1) {
                throw new DomainValidationException("Risk assessment result " + assessmentId
                        + " is not bound to a NIST_SP800_30_R1 methodology profile");
            }
            return List.of(row);
        }
        if (riskScenarioId != null) {
            return repository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, riskScenarioId);
        }
        return repository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
    }

    private NistAssessmentResult.NistAssessmentItem toItem(RiskAssessmentResult row) {
        Map<String, Object> inputs = row.getInputFactors() == null ? Map.of() : row.getInputFactors();
        Map<String, Object> persistedOutputs = row.getComputedOutputs() == null ? Map.of() : row.getComputedOutputs();
        MethodologyProfile profile = row.getMethodologyProfile();

        List<String> limitations = new ArrayList<>();

        ThreatEventKind eventKind = parseEnum(
                ThreatEventKind.class, stringValue(inputs.get("threat_event_kind")), "threat_event_kind", limitations);

        ThreatSourceRelevance relevance = parseEnum(
                ThreatSourceRelevance.class,
                stringValue(inputs.get("threat_source_relevance")),
                "threat_source_relevance",
                limitations);

        NistLikelihoodBand initiation = parseEnum(
                NistLikelihoodBand.class,
                stringValue(inputs.get("likelihood_initiation")),
                "likelihood_initiation",
                limitations);
        NistLikelihoodBand adverseImpact = parseEnum(
                NistLikelihoodBand.class,
                stringValue(inputs.get("likelihood_adverse_impact")),
                "likelihood_adverse_impact",
                limitations);
        // likelihood_overall: missing-value (null/blank) is NOT a limitation by
        // itself — the service derives overall from initiation × adverse-impact
        // per NIST Table G-5 when absent — but an invalid value IS a limitation
        // and must be reported.
        NistLikelihoodBand overallSupplied = parseEnumIgnoringMissing(
                NistLikelihoodBand.class,
                stringValue(inputs.get("likelihood_overall")),
                "likelihood_overall",
                limitations);
        NistImpactBand impact =
                parseEnum(NistImpactBand.class, stringValue(inputs.get("impact_level")), "impact_level", limitations);

        // Honor analyst-approved persisted outputs (computedOutputs) when present
        // so the analysis view never diverges from the durable RiskAssessmentResult.
        // Only derive missing values from inputs.
        NistLikelihoodBand persistedOverall = parseEnumIgnoringMissing(
                NistLikelihoodBand.class,
                stringValue(persistedOutputs.get("overall_likelihood")),
                "computedOutputs.overall_likelihood",
                limitations);
        NistImpactBand persistedImpact = parseEnumIgnoringMissing(
                NistImpactBand.class,
                stringValue(persistedOutputs.get("impact_level")),
                "computedOutputs.impact_level",
                limitations);
        String persistedRiskLevel = stringValue(persistedOutputs.get("risk_level"));
        String persistedMatrixCell = stringValue(persistedOutputs.get("matrix_cell"));
        String persistedDerivation = stringValue(persistedOutputs.get("derivation"));

        NistLikelihoodBand overall;
        String overallDerivation;
        if (persistedOverall != null) {
            overall = persistedOverall;
            overallDerivation = persistedDerivation != null
                    ? "persisted: " + persistedDerivation
                    : "persisted (computedOutputs.overall_likelihood)";
        } else if (overallSupplied != null) {
            overall = overallSupplied;
            overallDerivation = "analyst-supplied (likelihood_overall input)";
        } else if (initiation != null && adverseImpact != null) {
            overall = minBand(initiation, adverseImpact);
            overallDerivation =
                    "derived: min(likelihood_initiation, likelihood_adverse_impact) per NIST SP 800-30 Rev. 1 Table G-5";
        } else {
            overall = null;
            overallDerivation = "not-derivable (missing likelihood inputs)";
        }

        NistImpactBand effectiveImpact = persistedImpact != null ? persistedImpact : impact;

        String riskLevel;
        if (persistedRiskLevel != null && !persistedRiskLevel.isBlank()) {
            riskLevel = persistedRiskLevel;
        } else {
            riskLevel = overall != null && effectiveImpact != null
                    ? riskLevelFrom(overall, effectiveImpact)
                    : "UNDETERMINED";
        }
        String matrixCell;
        if (persistedMatrixCell != null && !persistedMatrixCell.isBlank()) {
            matrixCell = persistedMatrixCell;
        } else {
            matrixCell = overall != null && effectiveImpact != null
                    ? String.format("L%d-I%d", overall.ordinal() + 1, effectiveImpact.ordinal() + 1)
                    : null;
        }

        if (eventKind == ThreatEventKind.NON_ADVERSARIAL && inputs.containsKey("threat_source_characteristics")) {
            limitations.add(
                    "threat_source_characteristics carries adversarial-only fields (capability/intent/targeting)"
                            + " on a non-adversarial event; these do not apply per NIST SP 800-30 Rev. 1 Appendix D");
        }
        if (!hasNonEmptyList(inputs.get("predisposing_conditions"))) {
            limitations.add("predisposing-condition coverage incomplete: no conditions enumerated");
        }
        if (relevance == null) {
            limitations.add("threat-source relevance not established");
        }

        var typedInputs = new NistAssessmentResult.Inputs(
                asMap(inputs.get("threat_source")),
                asMap(inputs.get("threat_event")),
                eventKind,
                asListOfMaps(inputs.get("vulnerabilities")),
                asListOfMaps(inputs.get("predisposing_conditions")),
                relevance,
                initiation,
                adverseImpact,
                overall,
                impact,
                asMap(inputs.get("assessment_timeframe")));

        var typedOutputs =
                new NistAssessmentResult.Outputs(overall, effectiveImpact, riskLevel, matrixCell, overallDerivation);

        return new NistAssessmentResult.NistAssessmentItem(
                row.getId(),
                row.getRiskScenario() == null ? null : row.getRiskScenario().getId(),
                profile.getId(),
                profile.getProfileKey(),
                profile.getFamily().name(),
                profile.getVersion(),
                row.getAssessmentAt(),
                row.getTimeHorizon(),
                row.getAnalystIdentity(),
                row.getApprovalState() == null ? null : row.getApprovalState().name(),
                typedInputs,
                typedOutputs,
                row.getEvidenceRefs() == null ? List.of() : List.copyOf(row.getEvidenceRefs()),
                List.copyOf(limitations));
    }

    /**
     * Parse an enum from a raw value. Both missing and invalid record a
     * limitation. Use this for required fields.
     */
    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, String fieldName, List<String> limitations) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ignored) {
            limitations.add("invalid " + fieldName + " value: \"" + raw + "\"");
            return null;
        }
    }

    /**
     * Parse an enum from a raw value. Missing (null/blank) returns null
     * silently; only an invalid (non-blank, non-matching) value records a
     * limitation. Use this for optional fields whose absence the caller
     * resolves a different way (for example, deriving an overall likelihood
     * from initiation × adverse-impact when the analyst did not supply one).
     */
    private static <E extends Enum<E>> E parseEnumIgnoringMissing(
            Class<E> type, String raw, String fieldName, List<String> limitations) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ignored) {
            limitations.add("invalid " + fieldName + " value: \"" + raw + "\"");
            return null;
        }
    }

    private static String stringValue(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean hasNonEmptyList(Object o) {
        return o instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? Collections.unmodifiableMap((Map<String, Object>) m) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(Collections.unmodifiableMap((Map<String, Object>) m));
            }
        }
        return List.copyOf(out);
    }

    private static NistLikelihoodBand minBand(NistLikelihoodBand a, NistLikelihoodBand b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static String riskLevelFrom(NistLikelihoodBand likelihood, NistImpactBand impact) {
        // NIST SP 800-30 Rev. 1 Table I-2 — qualitative risk-level matrix.
        // Likelihood rows (VERY_LOW..VERY_HIGH) × Impact cols (VERY_LOW..VERY_HIGH).
        String[][] matrix = {
            {"VERY_LOW", "VERY_LOW", "VERY_LOW", "LOW", "LOW"},
            {"VERY_LOW", "LOW", "LOW", "LOW", "MODERATE"},
            {"VERY_LOW", "LOW", "MODERATE", "MODERATE", "HIGH"},
            {"LOW", "MODERATE", "MODERATE", "HIGH", "VERY_HIGH"},
            {"LOW", "MODERATE", "HIGH", "VERY_HIGH", "VERY_HIGH"},
        };
        return matrix[likelihood.ordinal()][impact.ordinal()];
    }
}
