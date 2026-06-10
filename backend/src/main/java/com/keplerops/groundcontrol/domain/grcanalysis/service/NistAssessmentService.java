package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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

    // Methodology-defined input map keys (NIST SP 800-30 Rev. 1 input vocabulary).
    private static final String KEY_THREAT_SOURCE = "threat_source";
    private static final String KEY_THREAT_EVENT = "threat_event";
    private static final String KEY_THREAT_EVENT_KIND = "threat_event_kind";
    private static final String KEY_THREAT_SOURCE_CHARACTERISTICS = "threat_source_characteristics";
    private static final String KEY_THREAT_SOURCE_RELEVANCE = "threat_source_relevance";
    private static final String KEY_VULNERABILITIES = "vulnerabilities";
    private static final String KEY_PREDISPOSING_CONDITIONS = "predisposing_conditions";
    private static final String KEY_LIKELIHOOD_INITIATION = "likelihood_initiation";
    private static final String KEY_LIKELIHOOD_ADVERSE_IMPACT = "likelihood_adverse_impact";
    private static final String KEY_LIKELIHOOD_OVERALL = "likelihood_overall";
    private static final String KEY_IMPACT_LEVEL = "impact_level";
    private static final String KEY_ASSESSMENT_TIMEFRAME = "assessment_timeframe";

    // Methodology-defined output map keys (in RiskAssessmentResult.computedOutputs).
    private static final String OUT_OVERALL_LIKELIHOOD = "overall_likelihood";
    private static final String OUT_IMPACT_LEVEL = "impact_level";
    private static final String OUT_RISK_LEVEL = "risk_level";
    private static final String OUT_MATRIX_CELL = "matrix_cell";
    private static final String OUT_DERIVATION = "derivation";

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
                .map(Project::getIdentifier)
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

        DecodedInputs decoded = decodeInputs(inputs, limitations);
        DerivedOverall derived = deriveOverall(decoded, inputs, persistedOutputs, limitations);
        ResolvedRisk resolved = resolveRisk(decoded, persistedOutputs, derived);

        applyContextLimitations(decoded, inputs, limitations);

        var typedInputs = new NistAssessmentResult.Inputs(
                asMap(inputs.get(KEY_THREAT_SOURCE)),
                asMap(inputs.get(KEY_THREAT_EVENT)),
                decoded.eventKind(),
                asListOfMaps(inputs.get(KEY_VULNERABILITIES)),
                asListOfMaps(inputs.get(KEY_PREDISPOSING_CONDITIONS)),
                decoded.relevance(),
                decoded.initiation(),
                decoded.adverseImpact(),
                derived.overall(),
                decoded.impact(),
                asMap(inputs.get(KEY_ASSESSMENT_TIMEFRAME)));

        var typedOutputs = new NistAssessmentResult.Outputs(
                derived.overall(),
                resolved.impact(),
                resolved.riskLevel(),
                resolved.matrixCell(),
                derived.derivation());

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

    private DecodedInputs decodeInputs(Map<String, Object> inputs, List<String> limitations) {
        return new DecodedInputs(
                parseEnum(
                        ThreatEventKind.class,
                        stringValue(inputs.get(KEY_THREAT_EVENT_KIND)),
                        KEY_THREAT_EVENT_KIND,
                        limitations),
                parseEnum(
                        ThreatSourceRelevance.class,
                        stringValue(inputs.get(KEY_THREAT_SOURCE_RELEVANCE)),
                        KEY_THREAT_SOURCE_RELEVANCE,
                        limitations),
                parseEnum(
                        NistLikelihoodBand.class,
                        stringValue(inputs.get(KEY_LIKELIHOOD_INITIATION)),
                        KEY_LIKELIHOOD_INITIATION,
                        limitations),
                parseEnum(
                        NistLikelihoodBand.class,
                        stringValue(inputs.get(KEY_LIKELIHOOD_ADVERSE_IMPACT)),
                        KEY_LIKELIHOOD_ADVERSE_IMPACT,
                        limitations),
                parseEnum(
                        NistImpactBand.class,
                        stringValue(inputs.get(KEY_IMPACT_LEVEL)),
                        KEY_IMPACT_LEVEL,
                        limitations));
    }

    /**
     * Decide the overall likelihood and provenance label. Persisted output
     * wins; analyst-supplied input is next; derive per Table G-5 last.
     */
    private DerivedOverall deriveOverall(
            DecodedInputs decoded,
            Map<String, Object> inputs,
            Map<String, Object> persistedOutputs,
            List<String> limitations) {
        // likelihood_overall as a supplied input: missing is fine (we'll derive),
        // but an invalid value still records a limitation.
        NistLikelihoodBand overallSupplied = parseEnumIgnoringMissing(
                NistLikelihoodBand.class,
                stringValue(inputs.get(KEY_LIKELIHOOD_OVERALL)),
                KEY_LIKELIHOOD_OVERALL,
                limitations);
        NistLikelihoodBand persistedOverall = parseEnumIgnoringMissing(
                NistLikelihoodBand.class,
                stringValue(persistedOutputs.get(OUT_OVERALL_LIKELIHOOD)),
                "computedOutputs." + OUT_OVERALL_LIKELIHOOD,
                limitations);
        String persistedDerivation = stringValue(persistedOutputs.get(OUT_DERIVATION));

        NistLikelihoodBand overall;
        String derivation;
        if (persistedOverall != null) {
            overall = persistedOverall;
            derivation = persistedDerivation != null
                    ? "persisted: " + persistedDerivation
                    : "persisted (computedOutputs." + OUT_OVERALL_LIKELIHOOD + ")";
        } else if (overallSupplied != null) {
            overall = overallSupplied;
            derivation = "analyst-supplied (likelihood_overall input)";
        } else if (decoded.initiation() != null && decoded.adverseImpact() != null) {
            overall = minBand(decoded.initiation(), decoded.adverseImpact());
            derivation =
                    "derived: min(likelihood_initiation, likelihood_adverse_impact) per NIST SP 800-30 Rev. 1 Table G-5";
        } else {
            overall = null;
            derivation = "not-derivable (missing likelihood inputs)";
        }
        return new DerivedOverall(overall, derivation);
    }

    private ResolvedRisk resolveRisk(
            DecodedInputs decoded, Map<String, Object> persistedOutputs, DerivedOverall derived) {
        // persisted impact_level wins over the input's impact_level for output
        // purposes (per the codex review fix: never diverge from the durable record).
        NistImpactBand persistedImpact = parseEnumIgnoringMissing(
                NistImpactBand.class,
                stringValue(persistedOutputs.get(OUT_IMPACT_LEVEL)),
                "computedOutputs." + OUT_IMPACT_LEVEL,
                new ArrayList<>()); // limitations on persisted output are recorded once via decoded inputs
        NistImpactBand effectiveImpact = persistedImpact != null ? persistedImpact : decoded.impact();

        String persistedRiskLevel = stringValue(persistedOutputs.get(OUT_RISK_LEVEL));
        String persistedMatrixCell = stringValue(persistedOutputs.get(OUT_MATRIX_CELL));

        String riskLevel;
        if (persistedRiskLevel != null && !persistedRiskLevel.isBlank()) {
            riskLevel = persistedRiskLevel;
        } else {
            riskLevel = derived.overall() != null && effectiveImpact != null
                    ? riskLevelFrom(derived.overall(), effectiveImpact)
                    : "UNDETERMINED";
        }

        String matrixCell;
        if (persistedMatrixCell != null && !persistedMatrixCell.isBlank()) {
            matrixCell = persistedMatrixCell;
        } else {
            matrixCell = derived.overall() != null && effectiveImpact != null
                    ? String.format("L%d-I%d", derived.overall().ordinal() + 1, effectiveImpact.ordinal() + 1)
                    : null;
        }

        return new ResolvedRisk(effectiveImpact, riskLevel, matrixCell);
    }

    private void applyContextLimitations(DecodedInputs decoded, Map<String, Object> inputs, List<String> limitations) {
        if (decoded.eventKind() == ThreatEventKind.NON_ADVERSARIAL
                && inputs.containsKey(KEY_THREAT_SOURCE_CHARACTERISTICS)) {
            limitations.add(
                    "threat_source_characteristics carries adversarial-only fields (capability/intent/targeting)"
                            + " on a non-adversarial event; these do not apply per NIST SP 800-30 Rev. 1 Appendix D");
        }
        if (!hasNonEmptyList(inputs.get(KEY_PREDISPOSING_CONDITIONS))) {
            limitations.add("predisposing-condition coverage incomplete: no conditions enumerated");
        }
        if (decoded.relevance() == null) {
            limitations.add("threat-source relevance not established");
        }
    }

    /**
     * Parse an enum from a raw value. Both missing and invalid record a
     * limitation. Use for fields that are mandatory at the methodology level.
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
     * limitation. Use for optional fields whose absence the caller resolves a
     * different way (for example, deriving overall likelihood from
     * initiation × adverse-impact when the analyst did not supply one).
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

    static String riskLevelFrom(NistLikelihoodBand likelihood, NistImpactBand impact) {
        // NIST SP 800-30 Rev. 1 Table I-2 — qualitative risk-level matrix.
        // Rows are likelihood (VERY_LOW..VERY_HIGH); columns are impact
        // (VERY_LOW..VERY_HIGH). Cell value is the band name.
        return RISK_MATRIX[likelihood.ordinal()][impact.ordinal()].name();
    }

    // Pre-resolved enum literals avoid duplicating the band string literals
    // across the matrix (SonarCloud java:S1192 — also keeps risk-level values
    // self-consistent with NistLikelihoodBand / NistImpactBand value sets).
    private static final NistLikelihoodBand VL = NistLikelihoodBand.VERY_LOW;
    private static final NistLikelihoodBand L = NistLikelihoodBand.LOW;
    private static final NistLikelihoodBand M = NistLikelihoodBand.MODERATE;
    private static final NistLikelihoodBand H = NistLikelihoodBand.HIGH;
    private static final NistLikelihoodBand VH = NistLikelihoodBand.VERY_HIGH;

    private static final NistLikelihoodBand[][] RISK_MATRIX = {
        {VL, VL, VL, L, L},
        {VL, L, L, L, M},
        {VL, L, M, M, H},
        {L, M, M, H, VH},
        {L, M, H, VH, VH},
    };

    private record DecodedInputs(
            ThreatEventKind eventKind,
            ThreatSourceRelevance relevance,
            NistLikelihoodBand initiation,
            NistLikelihoodBand adverseImpact,
            NistImpactBand impact) {}

    private record DerivedOverall(NistLikelihoodBand overall, String derivation) {}

    private record ResolvedRisk(NistImpactBand impact, String riskLevel, String matrixCell) {}
}
