package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
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
 * FAIR v3.0 quantitative risk analysis view per GC-T011. Reads existing
 * {@link RiskAssessmentResult} rows whose {@link MethodologyProfile} family is
 * {@link MethodologyFamily#FAIR}, decodes the methodology-defined input map,
 * derives Loss Event Frequency (LEF), Loss Magnitude (LM), and Annualized Loss
 * Expectancy (ALE) via three-point estimation, and returns a
 * methodology-attributed envelope.
 *
 * <p>Derivation precedence (persisted wins):
 * <ol>
 *   <li>computedOutputs.loss_event_frequency / annualized_loss_expectancy → "persisted"</li>
 *   <li>Analyst-supplied loss_event_frequency input → "analyst-supplied"</li>
 *   <li>TEF × Vulnerability → "derived: LEF = TEF × Vulnerability"</li>
 * </ol>
 *
 * <p>The service is read-only — it never mutates {@code RiskAssessmentResult}.
 */
@Service
@Transactional(readOnly = true)
public class FairQuantitativeAnalysisService {

    static final String ANALYSIS_KIND = "fair_quantitative";
    static final String DERIVATION_METHOD = "fair-v3.0-three-point-v1";
    static final String SCALE = "continuous";
    static final String UNITS = "monetary";

    // Input factor map keys (FAIR v3.0 vocabulary)
    private static final String KEY_TEF = "threat_event_frequency";
    private static final String KEY_CONTACT_FREQUENCY = "contact_frequency";
    private static final String KEY_PROB_OF_ACTION = "probability_of_action";
    private static final String KEY_VULNERABILITY = "vulnerability";
    private static final String KEY_THREAT_CAPABILITY = "threat_capability";
    private static final String KEY_RESISTANCE_STRENGTH = "resistance_strength";
    private static final String KEY_LEF = "loss_event_frequency";
    private static final String KEY_PLM = "primary_loss_magnitude";
    private static final String KEY_SLEF = "secondary_loss_event_frequency";
    private static final String KEY_SLM = "secondary_loss_magnitude";
    private static final String KEY_FAIR_CAM = "fair_cam";
    private static final String KEY_FAIR_MAM = "fair_mam";
    private static final String KEY_CURRENCY = "currency";

    // Computed output map keys
    private static final String OUT_LEF = "loss_event_frequency";
    private static final String OUT_ALE = "annualized_loss_expectancy";
    private static final String OUT_RISK_LEVEL = "risk_level";

    private final RiskAssessmentResultRepository repository;
    private final ProjectRepository projectRepository;

    public FairQuantitativeAnalysisService(
            RiskAssessmentResultRepository repository, ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    public FairQuantitativeAnalysisResult analyze(
            UUID projectId, Instant asOf, UUID assessmentId, UUID riskScenarioId) {
        Instant effectiveAsOf = asOf == null ? Instant.now() : asOf;
        String projectIdentifier = projectRepository
                .findById(projectId)
                .map(Project::getIdentifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        List<RiskAssessmentResult> rows = loadRows(projectId, assessmentId, riskScenarioId);

        List<FairQuantitativeAnalysisResult.FairAssessmentItem> items = new ArrayList<>();
        Map<String, Integer> byRiskLevel = new LinkedHashMap<>();
        int withLimitations = 0;

        // Determine the project-level currency from the first FAIR row that has a PLM currency
        String projectCurrency = "USD";
        for (RiskAssessmentResult row : rows) {
            if (row.getMethodologyProfile() == null
                    || row.getMethodologyProfile().getFamily() != MethodologyFamily.FAIR) {
                continue;
            }
            Map<String, Object> inputs = row.getInputFactors() == null ? Map.of() : row.getInputFactors();
            Map<String, Object> plm = asMap(inputs.get(KEY_PLM));
            if (plm != null && plm.containsKey(KEY_CURRENCY)) {
                projectCurrency = String.valueOf(plm.get(KEY_CURRENCY));
                break;
            }
        }

        for (RiskAssessmentResult row : rows) {
            if (row.getMethodologyProfile() == null
                    || row.getMethodologyProfile().getFamily() != MethodologyFamily.FAIR) {
                continue;
            }
            FairQuantitativeAnalysisResult.FairAssessmentItem item = toItem(row);
            items.add(item);
            if (item.outputs().riskLevel() != null) {
                byRiskLevel.merge(item.outputs().riskLevel(), 1, Integer::sum);
            }
            if (!item.limitations().isEmpty()) {
                withLimitations++;
            }
        }

        var counts = new FairQuantitativeAnalysisResult.Counts(items.size(), byRiskLevel, withLimitations);
        return new FairQuantitativeAnalysisResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                projectCurrency,
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
            if (profile == null || profile.getFamily() != MethodologyFamily.FAIR) {
                throw new DomainValidationException(
                        "Risk assessment result " + assessmentId + " is not bound to a FAIR methodology profile");
            }
            return List.of(row);
        }
        if (riskScenarioId != null) {
            return repository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, riskScenarioId);
        }
        return repository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
    }

    private FairQuantitativeAnalysisResult.FairAssessmentItem toItem(RiskAssessmentResult row) {
        Map<String, Object> inputs = row.getInputFactors() == null ? Map.of() : row.getInputFactors();
        Map<String, Object> persistedOutputs = row.getComputedOutputs() == null ? Map.of() : row.getComputedOutputs();
        MethodologyProfile profile = row.getMethodologyProfile();
        List<String> limitations = new ArrayList<>();

        // Extract all FAIR factor maps
        Map<String, Object> tef = asMap(inputs.get(KEY_TEF));
        Map<String, Object> cf = asMap(inputs.get(KEY_CONTACT_FREQUENCY));
        Map<String, Object> poa = asMap(inputs.get(KEY_PROB_OF_ACTION));
        Map<String, Object> vuln = asMap(inputs.get(KEY_VULNERABILITY));
        Map<String, Object> tcap = asMap(inputs.get(KEY_THREAT_CAPABILITY));
        Map<String, Object> rs = asMap(inputs.get(KEY_RESISTANCE_STRENGTH));
        Map<String, Object> analystLef = asMap(inputs.get(KEY_LEF));
        Map<String, Object> plm = asMap(inputs.get(KEY_PLM));
        Map<String, Object> slef = asMap(inputs.get(KEY_SLEF));
        Map<String, Object> slm = asMap(inputs.get(KEY_SLM));
        Map<String, Object> fairCam = asMap(inputs.get(KEY_FAIR_CAM));
        Map<String, Object> fairMam = asMap(inputs.get(KEY_FAIR_MAM));
        Map<String, Object> uncertainty = row.getUncertaintyMetadata() == null ? null : row.getUncertaintyMetadata();

        // Currency
        String currency = "USD";
        if (plm != null && plm.containsKey(KEY_CURRENCY)) {
            currency = String.valueOf(plm.get(KEY_CURRENCY));
        }

        // -----------------------------------------------------------------------
        // Single FAIR invariant validation pass — applied uniformly to every factor
        // before any LEF/LM/ALE arithmetic.
        //
        // Invariants enforced (FAIR v3.0 + FAIR-MAM schema semantics):
        //   1. Three-point ordering: low <= likely <= high for every factor that
        //      provides all three.
        //   2. Non-negativity: frequency and monetary factors must be >= 0.
        //   3. Probability bounds [0,1]: factors whose schema declares maximum=1
        //      (vulnerability, threat_capability, resistance_strength,
        //      secondary_loss_event_frequency) must stay within [0,1].
        //   4. Currency consistency: PLM and SLM currencies must match before the
        //      SLM contribution is added into the USD magnitude.
        //
        // Any violated factor is flagged with a specific limitation; further down,
        // derivation steps check the corresponding valid flag before executing.
        // -----------------------------------------------------------------------

        // Probability-bounded [0,1] factors — return values gate downstream derivation steps;
        // sub-factors (tcap, rs) are validated for invariants but are not direct derivation inputs.
        boolean vulnValid = validateThreePointFactor(KEY_VULNERABILITY, vuln, true, limitations);
        validateThreePointFactor(KEY_THREAT_CAPABILITY, tcap, true, limitations);
        validateThreePointFactor(KEY_RESISTANCE_STRENGTH, rs, true, limitations);
        boolean slefValid = validateThreePointFactor(KEY_SLEF, slef, true, limitations);

        // Non-negative frequency factors (no upper bound) — cf and poa are sub-factors only.
        boolean tefValid = validateThreePointFactor(KEY_TEF, tef, false, limitations);
        validateThreePointFactor(KEY_CONTACT_FREQUENCY, cf, false, limitations);
        validateThreePointFactor(KEY_PROB_OF_ACTION, poa, false, limitations);
        boolean analystLefValid = validateThreePointFactor(KEY_LEF, analystLef, false, limitations);

        // Non-negative monetary factors (no upper bound)
        boolean plmValid = validateThreePointFactor(KEY_PLM, plm, false, limitations);
        boolean slmValid = validateThreePointFactor(KEY_SLM, slm, false, limitations);

        // Currency consistency for SLM: must match PLM currency before combining
        boolean currenciesMatch = true;
        if (slm != null && slm.containsKey(KEY_CURRENCY)) {
            String slmCurrency = String.valueOf(slm.get(KEY_CURRENCY));
            if (!slmCurrency.equals(currency)) {
                currenciesMatch = false;
                limitations.add("mixed currencies not converted: primary_loss_magnitude uses " + currency
                        + " but secondary_loss_magnitude uses " + slmCurrency
                        + " — loss magnitude non-derivable from secondary");
            }
        }

        // Sub-factor completeness warnings (structural, not invariant violations)
        if (tef != null && cf == null && poa == null) {
            limitations.add(
                    "threat_event_frequency provided without contact_frequency and probability_of_action sub-factors");
        }
        if (vuln != null && tcap == null && rs == null) {
            limitations.add("vulnerability provided without threat_capability and resistance_strength sub-factors");
        }

        // Derive LEF
        FairQuantitativeAnalysisResult.ThreePoint lefResult = null;
        String lefDerivation = null;

        // 1. Check persisted computedOutputs.loss_event_frequency
        Map<String, Object> persistedLef = asMap(persistedOutputs.get(OUT_LEF));
        if (persistedLef != null) {
            Double pLow = asDouble(persistedLef.get("low"));
            Double pLikely = asDouble(persistedLef.get("likely"));
            Double pHigh = asDouble(persistedLef.get("high"));
            if (pLow != null && pLikely != null && pHigh != null) {
                lefResult = new FairQuantitativeAnalysisResult.ThreePoint(pLow, pLikely, pHigh);
                lefDerivation = "persisted";
            }
        }

        // 2. Analyst-supplied loss_event_frequency input (only if invariants hold)
        if (lefResult == null && analystLef != null && analystLefValid) {
            Double aLow = asDouble(analystLef.get("low"));
            Double aLikely = asDouble(analystLef.get("likely"));
            Double aHigh = asDouble(analystLef.get("high"));
            if (aLow != null && aLikely != null && aHigh != null) {
                lefResult = new FairQuantitativeAnalysisResult.ThreePoint(aLow, aLikely, aHigh);
                lefDerivation = "analyst-supplied";
            }
        }

        // 3. Derive from TEF × Vulnerability (only if both factors are invariant-valid)
        if (lefResult == null) {
            if (tef != null && vuln != null && tefValid && vulnValid) {
                Double tLow = asDouble(tef.get("low"));
                Double tLikely = asDouble(tef.get("likely"));
                Double tHigh = asDouble(tef.get("high"));
                Double vLow = asDouble(vuln.get("low"));
                Double vLikely = asDouble(vuln.get("likely"));
                Double vHigh = asDouble(vuln.get("high"));
                if (tLow != null
                        && tLikely != null
                        && tHigh != null
                        && vLow != null
                        && vLikely != null
                        && vHigh != null) {
                    lefResult = new FairQuantitativeAnalysisResult.ThreePoint(
                            tLow * vLow, tLikely * vLikely, tHigh * vHigh);
                    lefDerivation = "derived: LEF = TEF × Vulnerability";
                } else {
                    limitations.add("not-derivable: required factor missing for LEF derivation");
                }
            } else if (tef == null && vuln == null) {
                limitations.add("not-derivable: required factor missing for LEF derivation");
            } else if (!tefValid || !vulnValid) {
                // Invariant violation already recorded; suppress LEF derivation
                limitations.add("not-derivable: LEF suppressed due to invariant violation in input factors");
            } else {
                limitations.add("not-derivable: required factor missing for LEF derivation");
            }
        }

        // Derive LM (PLM + SLEF*SLM) — only when PLM invariants hold
        FairQuantitativeAnalysisResult.ThreePoint lmResult = null;
        if (plm == null) {
            limitations.add("not-derivable: primary_loss_magnitude missing");
        } else if (!plmValid) {
            // Invariant violation already recorded above; suppress LM
        } else {
            Double pLow = asDouble(plm.get("low"));
            Double pLikely = asDouble(plm.get("likely"));
            Double pHigh = asDouble(plm.get("high"));
            if (pLow != null && pLikely != null && pHigh != null) {
                double lmLow = pLow;
                double lmLikely = pLikely;
                double lmHigh = pHigh;
                // Add secondary loss only when both SLEF and SLM are valid AND currencies match
                if (slef != null && slm != null && slefValid && slmValid && currenciesMatch) {
                    Double seLow = asDouble(slef.get("low"));
                    Double seLikely = asDouble(slef.get("likely"));
                    Double seHigh = asDouble(slef.get("high"));
                    Double smLow = asDouble(slm.get("low"));
                    Double smLikely = asDouble(slm.get("likely"));
                    Double smHigh = asDouble(slm.get("high"));
                    if (seLow != null
                            && seLikely != null
                            && seHigh != null
                            && smLow != null
                            && smLikely != null
                            && smHigh != null) {
                        lmLow += seLow * smLow;
                        lmLikely += seLikely * smLikely;
                        lmHigh += seHigh * smHigh;
                    }
                }
                lmResult = new FairQuantitativeAnalysisResult.ThreePoint(lmLow, lmLikely, lmHigh);
            }
        }

        // Derive ALE (persisted wins)
        FairQuantitativeAnalysisResult.ThreePoint aleResult = null;
        Map<String, Object> alePercentiles = null;
        boolean aleIsPersisted = false;

        Map<String, Object> persistedAle = asMap(persistedOutputs.get(OUT_ALE));
        if (persistedAle != null) {
            Double aLow = asDouble(persistedAle.get("low"));
            Double aLikely = asDouble(persistedAle.get("likely"));
            Double aHigh = asDouble(persistedAle.get("high"));
            if (aLow != null && aLikely != null && aHigh != null) {
                aleResult = new FairQuantitativeAnalysisResult.ThreePoint(aLow, aLikely, aHigh);
                aleIsPersisted = true;
                // Extract persisted currency override for ALE
                if (persistedAle.containsKey(KEY_CURRENCY)) {
                    currency = String.valueOf(persistedAle.get(KEY_CURRENCY));
                }
                // Extract percentiles from persisted ALE
                alePercentiles = asMap(persistedAle.get("percentiles"));
            }
        }

        if (aleResult == null) {
            if (lefResult != null && lmResult != null) {
                aleResult = new FairQuantitativeAnalysisResult.ThreePoint(
                        lefResult.low() * lmResult.low(),
                        lefResult.likely() * lmResult.likely(),
                        lefResult.high() * lmResult.high());
                // ALE computed without Monte Carlo → emit limitation
                limitations.add("ALE percentiles absent (Monte-Carlo not recomputed)");
            }
        }

        // Build derivation string (ALE derivation drives the overall label)
        String derivation;
        if (aleIsPersisted) {
            derivation = "persisted";
        } else if (lefDerivation != null && aleResult != null) {
            derivation = lefDerivation;
        } else if (lefDerivation != null) {
            derivation = lefDerivation;
        } else {
            derivation = "not-derivable";
        }

        // Risk level passthrough from persisted outputs
        String riskLevel = null;
        Object rawRiskLevel = persistedOutputs.get(OUT_RISK_LEVEL);
        if (rawRiskLevel != null && !rawRiskLevel.toString().isBlank()) {
            riskLevel = rawRiskLevel.toString();
        }

        var typedInputs = new FairQuantitativeAnalysisResult.Inputs(
                tef, cf, poa, vuln, tcap, rs, analystLef, plm, slef, slm, fairCam, fairMam, uncertainty);

        var typedOutputs = new FairQuantitativeAnalysisResult.Outputs(
                lefResult, lmResult, aleResult, currency, alePercentiles, riskLevel, derivation);

        return new FairQuantitativeAnalysisResult.FairAssessmentItem(
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
     * Single FAIR quantitative invariant validation gate. Checks all three-point
     * invariants for one factor map and appends specific limitation messages for
     * any violation. Returns {@code true} if the factor is valid for use in
     * derivation; {@code false} if any invariant was breached.
     *
     * <p>Invariants checked (in order):
     * <ol>
     *   <li>Non-negativity: every low/likely/high value must be {@code >= 0}.</li>
     *   <li>Probability bound [0,1]: when {@code isProbabilityBounded} is true,
     *       every value must be {@code <= 1}. Per the FAIR schema, this applies to
     *       vulnerability, threat_capability, resistance_strength, and
     *       secondary_loss_event_frequency.</li>
     *   <li>Three-point ordering: {@code low <= likely <= high}.</li>
     * </ol>
     *
     * @param factorName          FAIR vocabulary key (for limitation messages)
     * @param factor              the three-point map ({@code low}, {@code likely}, {@code high})
     * @param isProbabilityBounded whether this factor is bounded to [0,1]
     * @param limitations         accumulator — violations are appended here
     * @return {@code true} if all present values satisfy the invariants
     */
    private static boolean validateThreePointFactor(
            String factorName, Map<String, Object> factor, boolean isProbabilityBounded, List<String> limitations) {
        if (factor == null) {
            return true; // absent factors are handled by the missing-input path, not here
        }
        Double low = asDouble(factor.get("low"));
        Double likely = asDouble(factor.get("likely"));
        Double high = asDouble(factor.get("high"));

        boolean valid = true;

        // Check each present value individually so the limitation names the offending slot
        String[] slots = {"low", "likely", "high"};
        Double[] vals = {low, likely, high};
        for (int i = 0; i < slots.length; i++) {
            if (vals[i] == null) {
                continue;
            }
            double v = vals[i];
            if (v < 0) {
                limitations.add(
                        factorName + " " + slots[i] + " value must be >= 0 (got " + v + ") — assessment non-derivable");
                valid = false;
            } else if (isProbabilityBounded && v > 1.0) {
                limitations.add(
                        factorName + " " + slots[i] + " out of [0,1] bounds: " + v + " — assessment non-derivable");
                valid = false;
            }
        }

        // Three-point ordering: low <= likely <= high (only when all three are present)
        if (low != null && likely != null && high != null) {
            if (low > likely || likely > high) {
                limitations.add(factorName + " range out of order (low=" + low + " likely=" + likely + " high=" + high
                        + ") — assessment non-derivable");
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Defensive numeric coercion. Handles Integer, Double, Number, and numeric
     * String values. Returns null for null or non-numeric inputs.
     */
    static Double asDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Double d) {
            return d;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? Collections.unmodifiableMap((Map<String, Object>) m) : null;
    }
}
