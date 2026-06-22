package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Clock;
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
 * Open FAIR quantitative risk analysis view per GC-T011, aligned to O-RT
 * 3.0.1 / O-RA 2.0.1 factor vocabulary. Reads existing
 * {@link RiskAssessmentResult} rows whose {@link MethodologyProfile} family is
 * {@link MethodologyFamily#FAIR}, decodes the methodology-defined input map,
 * derives Loss Event Frequency (LEF), Loss Magnitude (LM), and Annualized Loss
 * Expectancy (ALE) via bounded three-point estimates, and returns a
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
    static final String DERIVATION_METHOD = "open-fair-o-rt3.0.1-o-ra2.0.1-three-point-v1";
    static final String SCALE = "continuous";
    static final String UNITS = "monetary";

    // Input factor map keys (Open FAIR / O-RT vocabulary)
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
    private static final String KEY_CURRENCY = "currency";
    // GC-T016: O-RT forms-of-loss materiality breakdown and stakeholder secondary effects.
    private static final String KEY_FORMS_OF_LOSS = "forms_of_loss";
    private static final String KEY_SECONDARY_BY_STAKEHOLDER = "secondary_loss_by_stakeholder";

    // secondary_loss_by_stakeholder entry keys (GC-T016)
    private static final String KEY_STAKEHOLDER = "stakeholder";
    private static final String KEY_LOSS_FORM = "loss_form";

    // Three-point map slot keys
    private static final String KEY_LOW = "low";
    private static final String KEY_LIKELY = "likely";
    private static final String KEY_HIGH = "high";

    // Not-derivable limitation messages
    private static final String LIMIT_LEF_MISSING_FACTOR = "not-derivable: required factor missing for LEF derivation";
    private static final double PROBABILITY_MAX = 1.0;
    private static final double PERCENTILE_MAX = 100.0;
    private static final String PROBABILITY_BOUNDS_LABEL = "[0,1]";

    // Computed output map keys
    private static final String OUT_LEF = "loss_event_frequency";
    private static final String OUT_ALE = "annualized_loss_expectancy";
    private static final String OUT_RISK_LEVEL = "risk_level";

    private final RiskAssessmentResultRepository repository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public FairQuantitativeAnalysisService(
            RiskAssessmentResultRepository repository, ProjectRepository projectRepository, Clock clock) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    public FairQuantitativeAnalysisResult analyze(
            UUID projectId, Instant asOf, UUID assessmentId, UUID riskScenarioId) {
        Instant effectiveAsOf = asOf == null ? Instant.now(clock) : asOf;
        String projectIdentifier = resolveProjectIdentifier(projectId);
        List<RiskAssessmentResult> rows = loadRows(projectId, assessmentId, riskScenarioId);
        String projectCurrency = resolveProjectCurrency(rows);
        return buildResult(projectIdentifier, effectiveAsOf, projectCurrency, rows);
    }

    private String resolveProjectIdentifier(UUID projectId) {
        return projectRepository
                .findById(projectId)
                .map(Project::getIdentifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }

    private String resolveProjectCurrency(List<RiskAssessmentResult> rows) {
        for (RiskAssessmentResult row : rows) {
            if (!isFairRow(row)) {
                continue;
            }
            Map<String, Object> inputs = row.getInputFactors() == null ? Map.of() : row.getInputFactors();
            Map<String, Object> plm = asMap(inputs.get(KEY_PLM));
            if (plm != null && plm.containsKey(KEY_CURRENCY)) {
                return String.valueOf(plm.get(KEY_CURRENCY));
            }
        }
        return "USD";
    }

    private FairQuantitativeAnalysisResult buildResult(
            String projectIdentifier, Instant effectiveAsOf, String projectCurrency, List<RiskAssessmentResult> rows) {
        List<FairQuantitativeAnalysisResult.FairAssessmentItem> items = new ArrayList<>();
        Map<String, Integer> byRiskLevel = new LinkedHashMap<>();
        int withLimitations = 0;

        for (RiskAssessmentResult row : rows) {
            if (!isFairRow(row)) {
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

    private static boolean isFairRow(RiskAssessmentResult row) {
        return row.getMethodologyProfile() != null
                && row.getMethodologyProfile().getFamily() == MethodologyFamily.FAIR;
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

        ParsedFactors f = new ParsedFactors(
                asMap(inputs.get(KEY_TEF)),
                asMap(inputs.get(KEY_CONTACT_FREQUENCY)),
                asMap(inputs.get(KEY_PROB_OF_ACTION)),
                asMap(inputs.get(KEY_VULNERABILITY)),
                asMap(inputs.get(KEY_THREAT_CAPABILITY)),
                asMap(inputs.get(KEY_RESISTANCE_STRENGTH)),
                asMap(inputs.get(KEY_LEF)),
                asMap(inputs.get(KEY_PLM)),
                asMap(inputs.get(KEY_SLEF)),
                asMap(inputs.get(KEY_SLM)),
                asMap(inputs.get(KEY_FORMS_OF_LOSS)),
                asList(inputs.get(KEY_SECONDARY_BY_STAKEHOLDER)),
                row.getUncertaintyMetadata());

        String currency = resolveCurrency(f.plm());

        ValidationFlags flags = runInvariantValidation(f, currency, limitations);

        addFactorLineageLimitations(f, limitations);

        FairLefResult lefComputed = deriveLossEventFrequency(persistedOutputs, f, flags, limitations);

        FairQuantitativeAnalysisResult.ThreePoint lmResult = deriveLossMagnitude(f, flags, limitations);

        FairAleResult aleComputed =
                deriveAnnualizedLossExpectancy(persistedOutputs, lefComputed.result(), lmResult, currency, limitations);

        String derivation = resolveDerivationLabel(aleComputed.isPersisted(), lefComputed.derivation());
        String riskLevel = resolveRiskLevel(persistedOutputs);

        var typedInputs = new FairQuantitativeAnalysisResult.Inputs(
                f.tef(),
                f.cf(),
                f.poa(),
                f.vuln(),
                f.tcap(),
                f.rs(),
                f.analystLef(),
                f.plm(),
                f.slef(),
                f.slm(),
                f.uncertainty());
        FairQuantitativeAnalysisResult.Materiality materiality = deriveMateriality(f, currency, limitations);

        var typedOutputs = new FairQuantitativeAnalysisResult.Outputs(
                lefComputed.result(),
                lmResult,
                aleComputed.result(),
                aleComputed.currency(),
                aleComputed.percentiles(),
                riskLevel,
                derivation,
                materiality);

        return assembleItem(row, profile, typedInputs, typedOutputs, limitations);
    }

    /** Resolves the currency from the PLM factor map, defaulting to USD. */
    private static String resolveCurrency(Map<String, Object> plm) {
        if (plm != null && plm.containsKey(KEY_CURRENCY)) {
            return String.valueOf(plm.get(KEY_CURRENCY));
        }
        return "USD";
    }

    /**
     * Runs the single FAIR invariant validation pass — applied uniformly to every factor
     * before any LEF/LM/ALE arithmetic.
     *
     * <p>Invariants enforced (Open FAIR schema semantics):
     * three-point ordering (low &lt;= likely &lt;= high), non-negativity, probability
     * bounds [0,1] for probability factors, percentile bounds [0,100] for
     * Threat Capability / Resistance Strength, and currency consistency between PLM and SLM.
     */
    private static ValidationFlags runInvariantValidation(ParsedFactors f, String currency, List<String> limitations) {
        // Probability-bounded [0,1] factors
        boolean vulnValid = validateThreePointFactor(
                KEY_VULNERABILITY, f.vuln(), PROBABILITY_MAX, PROBABILITY_BOUNDS_LABEL, limitations);
        boolean poaValid = validateThreePointFactor(
                KEY_PROB_OF_ACTION, f.poa(), PROBABILITY_MAX, PROBABILITY_BOUNDS_LABEL, limitations);
        boolean slefValid =
                validateThreePointFactor(KEY_SLEF, f.slef(), PROBABILITY_MAX, PROBABILITY_BOUNDS_LABEL, limitations);

        // Percentile-scale force factors. They are not probabilities.
        boolean tcapValid =
                validateThreePointFactor(KEY_THREAT_CAPABILITY, f.tcap(), PERCENTILE_MAX, "[0,100]", limitations);
        boolean rsValid =
                validateThreePointFactor(KEY_RESISTANCE_STRENGTH, f.rs(), PERCENTILE_MAX, "[0,100]", limitations);

        // Non-negative frequency factors (no upper bound); cf is a sub-factor only.
        boolean tefValid = validateThreePointFactor(KEY_TEF, f.tef(), null, null, limitations);
        boolean cfValid = validateThreePointFactor(KEY_CONTACT_FREQUENCY, f.cf(), null, null, limitations);
        boolean analystLefValid = validateThreePointFactor(KEY_LEF, f.analystLef(), null, null, limitations);

        // Non-negative monetary factors (no upper bound)
        boolean plmValid = validateThreePointFactor(KEY_PLM, f.plm(), null, null, limitations);
        boolean slmValid = validateThreePointFactor(KEY_SLM, f.slm(), null, null, limitations);

        // Currency consistency for SLM: must match PLM currency before combining
        boolean currenciesMatch = true;
        if (f.slm() != null && f.slm().containsKey(KEY_CURRENCY)) {
            String slmCurrency = String.valueOf(f.slm().get(KEY_CURRENCY));
            if (!slmCurrency.equals(currency)) {
                currenciesMatch = false;
                limitations.add("mixed currencies not converted: primary_loss_magnitude uses " + currency
                        + " but secondary_loss_magnitude uses " + slmCurrency
                        + " — loss magnitude non-derivable from secondary");
            }
        }

        return new ValidationFlags(
                tefValid,
                cfValid,
                poaValid,
                vulnValid,
                tcapValid,
                rsValid,
                analystLefValid,
                plmValid,
                slmValid,
                slefValid,
                currenciesMatch);
    }

    private static void addFactorLineageLimitations(ParsedFactors f, List<String> limitations) {
        if (f.tef() == null && ((f.cf() == null) != (f.poa() == null))) {
            limitations.add("threat_event_frequency not derivable: contact_frequency and probability_of_action "
                    + "must both be present when threat_event_frequency is not supplied");
        }
        if ((f.tcap() == null) != (f.rs() == null)) {
            limitations.add("vulnerability not derivable: threat_capability and resistance_strength must both be "
                    + "present to evaluate P(TCap > RS)");
        }
        if (f.vuln() == null && f.tcap() != null && f.rs() != null) {
            limitations.add("vulnerability not derived from threat_capability/resistance_strength: distribution "
                    + "functions or Monte Carlo samples are required to evaluate P(TCap > RS)");
        }
    }

    /**
     * All parsed three-point factor maps extracted from a single row's input-factors map.
     * Passed as a single carrier to derivation helpers to avoid wide parameter lists.
     */
    private record ParsedFactors(
            Map<String, Object> tef,
            Map<String, Object> cf,
            Map<String, Object> poa,
            Map<String, Object> vuln,
            Map<String, Object> tcap,
            Map<String, Object> rs,
            Map<String, Object> analystLef,
            Map<String, Object> plm,
            Map<String, Object> slef,
            Map<String, Object> slm,
            Map<String, Object> formsOfLoss,
            List<Object> secondaryByStakeholder,
            Map<String, Object> uncertainty) {}

    /**
     * Validation flags produced by a single FAIR invariant validation pass.
     * Each boolean corresponds to one factor or cross-factor check.
     */
    private record ValidationFlags(
            boolean tefValid,
            boolean cfValid,
            boolean poaValid,
            boolean vulnValid,
            boolean tcapValid,
            boolean rsValid,
            boolean analystLefValid,
            boolean plmValid,
            boolean slmValid,
            boolean slefValid,
            boolean currenciesMatch) {}

    /**
     * Result carrier for LEF derivation — holds the three-point value (may be null when
     * not derivable) and the derivation label string.
     */
    private record FairLefResult(FairQuantitativeAnalysisResult.ThreePoint result, String derivation) {}

    /**
     * Result carrier for Threat Event Frequency derivation.
     */
    private record FairTefResult(FairQuantitativeAnalysisResult.ThreePoint result, String derivation) {}

    /**
     * Derives Loss Event Frequency using precedence order: persisted outputs, then
     * analyst-supplied input, then TEF × Vulnerability arithmetic.
     */
    private static FairLefResult deriveLossEventFrequency(
            Map<String, Object> persistedOutputs, ParsedFactors f, ValidationFlags flags, List<String> limitations) {
        // 1. Check persisted computedOutputs.loss_event_frequency
        FairLefResult persisted = tryPersistedLef(persistedOutputs);
        if (persisted != null) {
            return persisted;
        }

        // 2. Analyst-supplied loss_event_frequency input (only if invariants hold)
        if (f.analystLef() != null && flags.analystLefValid()) {
            FairQuantitativeAnalysisResult.ThreePoint tp = parseThreePoint(f.analystLef());
            if (tp != null) {
                return new FairLefResult(tp, "analyst-supplied");
            }
        }

        // 3. Derive from TEF × Vulnerability. TEF may itself be supplied directly or
        // derived from Contact Frequency × Probability of Action.
        FairTefResult tef = resolveThreatEventFrequency(f, flags, limitations);
        return deriveLefFromTefVuln(tef.result(), tef.derivation(), f.vuln(), flags.vulnValid(), limitations);
    }

    private static FairLefResult tryPersistedLef(Map<String, Object> persistedOutputs) {
        Map<String, Object> persistedLef = asMap(persistedOutputs.get(OUT_LEF));
        if (persistedLef == null) {
            return null;
        }
        FairQuantitativeAnalysisResult.ThreePoint tp = parseThreePoint(persistedLef);
        return tp != null ? new FairLefResult(tp, "persisted") : null;
    }

    private static FairTefResult resolveThreatEventFrequency(
            ParsedFactors f, ValidationFlags flags, List<String> limitations) {
        if (f.tef() != null) {
            FairQuantitativeAnalysisResult.ThreePoint supplied = parseThreePoint(f.tef());
            if (supplied != null && flags.tefValid()) {
                return new FairTefResult(supplied, "supplied TEF");
            }
            limitations.add("not-derivable: threat_event_frequency suppressed due to invariant violation");
            return new FairTefResult(null, null);
        }
        if (f.cf() == null && f.poa() == null) {
            return new FairTefResult(null, null);
        }
        if (f.cf() != null && f.poa() != null && flags.cfValid() && flags.poaValid()) {
            FairQuantitativeAnalysisResult.ThreePoint cf = parseThreePoint(f.cf());
            FairQuantitativeAnalysisResult.ThreePoint poa = parseThreePoint(f.poa());
            if (cf != null && poa != null) {
                var derived = new FairQuantitativeAnalysisResult.ThreePoint(
                        cf.low() * poa.low(), cf.likely() * poa.likely(), cf.high() * poa.high());
                return new FairTefResult(derived, "derived: TEF = Contact Frequency × Probability of Action");
            }
            limitations.add("not-derivable: required factor missing for TEF derivation");
            return new FairTefResult(null, null);
        }
        if (!flags.cfValid() || !flags.poaValid()) {
            limitations.add("not-derivable: threat_event_frequency suppressed due to invariant violation in "
                    + "contact_frequency/probability_of_action");
        }
        return new FairTefResult(null, null);
    }

    private static FairLefResult deriveLefFromTefVuln(
            FairQuantitativeAnalysisResult.ThreePoint tef,
            String tefDerivation,
            Map<String, Object> vuln,
            boolean vulnValid,
            List<String> limitations) {
        if (tef != null && vuln != null && vulnValid) {
            Double vLow = asDouble(vuln.get(KEY_LOW));
            Double vLikely = asDouble(vuln.get(KEY_LIKELY));
            Double vHigh = asDouble(vuln.get(KEY_HIGH));
            if (vLow != null && vLikely != null && vHigh != null) {
                var tp = new FairQuantitativeAnalysisResult.ThreePoint(
                        tef.low() * vLow, tef.likely() * vLikely, tef.high() * vHigh);
                String derivation = "derived: LEF = TEF × Vulnerability";
                if (tefDerivation != null && tefDerivation.startsWith("derived:")) {
                    derivation = "derived: LEF = (Contact Frequency × Probability of Action) × Vulnerability";
                }
                return new FairLefResult(tp, derivation);
            }
            limitations.add(LIMIT_LEF_MISSING_FACTOR);
        } else if (!vulnValid) {
            // Invariant violation already recorded; suppress LEF derivation
            limitations.add("not-derivable: LEF suppressed due to invariant violation in input factors");
        } else {
            limitations.add(LIMIT_LEF_MISSING_FACTOR);
        }
        return new FairLefResult(null, null);
    }

    /** Derives Loss Magnitude from PLM plus optional SLEF×SLM secondary loss. */
    private static FairQuantitativeAnalysisResult.ThreePoint deriveLossMagnitude(
            ParsedFactors f, ValidationFlags flags, List<String> limitations) {
        if (f.plm() == null) {
            limitations.add("not-derivable: primary_loss_magnitude missing");
            return null;
        }
        if (!flags.plmValid()) {
            // Invariant violation already recorded above; suppress LM
            return null;
        }
        Double pLow = asDouble(f.plm().get(KEY_LOW));
        Double pLikely = asDouble(f.plm().get(KEY_LIKELY));
        Double pHigh = asDouble(f.plm().get(KEY_HIGH));
        if (pLow == null || pLikely == null || pHigh == null) {
            return null;
        }
        double lmLow = pLow;
        double lmLikely = pLikely;
        double lmHigh = pHigh;
        // Add secondary loss only when both SLEF and SLM are valid AND currencies match
        if (f.slef() != null && f.slm() != null && flags.slefValid() && flags.slmValid() && flags.currenciesMatch()) {
            Double seLow = asDouble(f.slef().get(KEY_LOW));
            Double seLikely = asDouble(f.slef().get(KEY_LIKELY));
            Double seHigh = asDouble(f.slef().get(KEY_HIGH));
            Double smLow = asDouble(f.slm().get(KEY_LOW));
            Double smLikely = asDouble(f.slm().get(KEY_LIKELY));
            Double smHigh = asDouble(f.slm().get(KEY_HIGH));
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
        return new FairQuantitativeAnalysisResult.ThreePoint(lmLow, lmLikely, lmHigh);
    }

    /**
     * Result carrier for ALE derivation — holds the three-point value, whether it came
     * from persisted outputs, the effective currency, and any percentile map.
     */
    private record FairAleResult(
            FairQuantitativeAnalysisResult.ThreePoint result,
            boolean isPersisted,
            String currency,
            Map<String, Object> percentiles) {}

    /** Derives Annualized Loss Expectancy; persisted outputs take precedence. */
    private static FairAleResult deriveAnnualizedLossExpectancy(
            Map<String, Object> persistedOutputs,
            FairQuantitativeAnalysisResult.ThreePoint lefResult,
            FairQuantitativeAnalysisResult.ThreePoint lmResult,
            String currency,
            List<String> limitations) {
        Map<String, Object> persistedAle = asMap(persistedOutputs.get(OUT_ALE));
        if (persistedAle != null) {
            Double aLow = asDouble(persistedAle.get(KEY_LOW));
            Double aLikely = asDouble(persistedAle.get(KEY_LIKELY));
            Double aHigh = asDouble(persistedAle.get(KEY_HIGH));
            if (aLow != null && aLikely != null && aHigh != null) {
                String effectiveCurrency = persistedAle.containsKey(KEY_CURRENCY)
                        ? String.valueOf(persistedAle.get(KEY_CURRENCY))
                        : currency;
                Map<String, Object> percentiles = asMap(persistedAle.get("percentiles"));
                var tp = new FairQuantitativeAnalysisResult.ThreePoint(aLow, aLikely, aHigh);
                return new FairAleResult(tp, true, effectiveCurrency, percentiles);
            }
        }

        if (lefResult != null && lmResult != null) {
            var tp = new FairQuantitativeAnalysisResult.ThreePoint(
                    lefResult.low() * lmResult.low(),
                    lefResult.likely() * lmResult.likely(),
                    lefResult.high() * lmResult.high());
            // ALE computed without Monte Carlo → emit limitation
            limitations.add("ALE percentiles absent (Monte-Carlo not recomputed)");
            return new FairAleResult(tp, false, currency, null);
        }

        return new FairAleResult(null, false, currency, null);
    }

    private static String resolveDerivationLabel(boolean aleIsPersisted, String lefDerivation) {
        if (aleIsPersisted) {
            return "persisted";
        }
        if (lefDerivation != null) {
            return lefDerivation;
        }
        return "not-derivable";
    }

    private static String resolveRiskLevel(Map<String, Object> persistedOutputs) {
        Object rawRiskLevel = persistedOutputs.get(OUT_RISK_LEVEL);
        if (rawRiskLevel != null && !rawRiskLevel.toString().isBlank()) {
            return rawRiskLevel.toString();
        }
        return null;
    }

    private static FairQuantitativeAnalysisResult.FairAssessmentItem assembleItem(
            RiskAssessmentResult row,
            MethodologyProfile profile,
            FairQuantitativeAnalysisResult.Inputs typedInputs,
            FairQuantitativeAnalysisResult.Outputs typedOutputs,
            List<String> limitations) {
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
     * Parses a three-point estimate map into a {@link FairQuantitativeAnalysisResult.ThreePoint}.
     * Returns null if any of low, likely, or high is absent.
     */
    private static FairQuantitativeAnalysisResult.ThreePoint parseThreePoint(Map<String, Object> factor) {
        Double low = asDouble(factor.get(KEY_LOW));
        Double likely = asDouble(factor.get(KEY_LIKELY));
        Double high = asDouble(factor.get(KEY_HIGH));
        if (low == null || likely == null || high == null) {
            return null;
        }
        return new FairQuantitativeAnalysisResult.ThreePoint(low, likely, high);
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
     *       every value must be within the provided upper bound. Per the Open FAIR
     *       schema, this applies to probability factors ([0,1]) and percentile-scale
     *       Threat Capability / Resistance Strength ([0,100]).</li>
     *   <li>Three-point ordering: {@code low <= likely <= high}.</li>
     * </ol>
     *
     * @param factorName          FAIR vocabulary key (for limitation messages)
     * @param factor              the three-point map ({@code low}, {@code likely}, {@code high})
     * @param maximum             optional inclusive upper bound
     * @param boundLabel          human-readable upper-bound label
     * @param limitations         accumulator — violations are appended here
     * @return {@code true} if all present values satisfy the invariants
     */
    private static boolean validateThreePointFactor(
            String factorName,
            Map<String, Object> factor,
            Double maximum,
            String boundLabel,
            List<String> limitations) {
        if (factor == null) {
            return true; // absent factors are handled by the missing-input path, not here
        }
        Double low = asDouble(factor.get(KEY_LOW));
        Double likely = asDouble(factor.get(KEY_LIKELY));
        Double high = asDouble(factor.get(KEY_HIGH));

        boolean valid = true;

        // Check each present value individually so the limitation names the offending slot
        String[] slots = {KEY_LOW, KEY_LIKELY, KEY_HIGH};
        Double[] vals = {low, likely, high};
        for (int i = 0; i < slots.length; i++) {
            if (vals[i] != null) {
                valid = checkSlotInvariant(factorName, slots[i], vals[i], maximum, boundLabel, limitations) && valid;
            }
        }

        // Three-point ordering: low <= likely <= high (only when all three are present)
        if (low != null && likely != null && high != null && (low > likely || likely > high)) {
            limitations.add(factorName + " range out of order (low=" + low + " likely=" + likely + " high=" + high
                    + ") — assessment non-derivable");
            valid = false;
        }

        return valid;
    }

    private static boolean checkSlotInvariant(
            String factorName, String slot, double v, Double maximum, String boundLabel, List<String> limitations) {
        if (v < 0) {
            limitations.add(factorName + " " + slot + " value must be >= 0 (got " + v + ") — assessment non-derivable");
            return false;
        }
        if (maximum != null && v > maximum) {
            limitations.add(factorName + " " + slot + " out of " + boundLabel + " bounds: " + v
                    + " — assessment non-derivable");
            return false;
        }
        return true;
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

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? Collections.unmodifiableList((List<Object>) l) : null;
    }

    /**
     * Builds the GC-T016 FAIR materiality view: a typed decomposition of the opaque
     * {@code forms_of_loss} loss-magnitude breakdown into the six O-RT forms of loss,
     * plus any stakeholder-specific secondary effects. Returns {@code null} when
     * neither input is present so callers can treat materiality as an optional view.
     *
     * <p>This view is descriptive only — it is deliberately NOT fed back into the
     * LEF/LM/ALE arithmetic, so populating {@code forms_of_loss} never shifts the
     * canonical ALE and assessments stay comparable across rows.
     */
    private static FairQuantitativeAnalysisResult.Materiality deriveMateriality(
            ParsedFactors f, String currency, List<String> limitations) {
        if (f.formsOfLoss() == null && f.secondaryByStakeholder() == null) {
            return null;
        }
        List<FairQuantitativeAnalysisResult.FormOfLossBreakdown> forms =
                decomposeFormsOfLoss(f.formsOfLoss(), currency, limitations);
        List<FairQuantitativeAnalysisResult.StakeholderSecondaryLoss> stakeholders =
                parseStakeholderSecondaryLosses(f.secondaryByStakeholder(), currency, limitations);
        FairQuantitativeAnalysisResult.ThreePoint total = sumForms(forms);
        return new FairQuantitativeAnalysisResult.Materiality(forms, total, currency, stakeholders);
    }

    /**
     * Decomposes the {@code forms_of_loss} map into one typed breakdown per present
     * O-RT form of loss. A form is excluded (with a limitation) when its currency
     * disagrees with the assessment currency or it breaches a three-point invariant,
     * mirroring the secondary-loss currency/invariant handling.
     */
    private static List<FairQuantitativeAnalysisResult.FormOfLossBreakdown> decomposeFormsOfLoss(
            Map<String, Object> formsOfLoss, String currency, List<String> limitations) {
        List<FairQuantitativeAnalysisResult.FormOfLossBreakdown> out = new ArrayList<>();
        if (formsOfLoss == null) {
            return out;
        }
        for (FairFormOfLoss form : FairFormOfLoss.values()) {
            FairQuantitativeAnalysisResult.FormOfLossBreakdown breakdown =
                    decomposeForm(form, asMap(formsOfLoss.get(form.jsonKey())), currency, limitations);
            if (breakdown != null) {
                out.add(breakdown);
            }
        }
        return out;
    }

    /** Decomposes one {@code forms_of_loss} form, or {@code null} when absent/mismatched/invalid. */
    private static FairQuantitativeAnalysisResult.FormOfLossBreakdown decomposeForm(
            FairFormOfLoss form, Map<String, Object> formMap, String currency, List<String> limitations) {
        if (formMap == null) {
            return null;
        }
        if (formMap.containsKey(KEY_CURRENCY)) {
            String formCurrency = String.valueOf(formMap.get(KEY_CURRENCY));
            if (!formCurrency.equals(currency)) {
                limitations.add("forms_of_loss " + form.jsonKey() + " uses currency " + formCurrency
                        + " but assessment currency is " + currency + " — excluded from materiality total");
                return null;
            }
        }
        if (!validateThreePointFactor("forms_of_loss." + form.jsonKey(), formMap, null, null, limitations)) {
            return null;
        }
        FairQuantitativeAnalysisResult.ThreePoint tp = parseThreePoint(formMap);
        return tp == null ? null : new FairQuantitativeAnalysisResult.FormOfLossBreakdown(form, tp);
    }

    /** Elementwise sum of all decomposed forms of loss; {@code null} when none are present. */
    private static FairQuantitativeAnalysisResult.ThreePoint sumForms(
            List<FairQuantitativeAnalysisResult.FormOfLossBreakdown> forms) {
        if (forms.isEmpty()) {
            return null;
        }
        double low = 0;
        double likely = 0;
        double high = 0;
        for (FairQuantitativeAnalysisResult.FormOfLossBreakdown b : forms) {
            low += b.magnitude().low();
            likely += b.magnitude().likely();
            high += b.magnitude().high();
        }
        return new FairQuantitativeAnalysisResult.ThreePoint(low, likely, high);
    }

    /**
     * Parses the optional {@code secondary_loss_by_stakeholder} array into typed
     * per-stakeholder secondary-loss effects. An entry whose currency disagrees
     * with the single-currency materiality envelope, or that breaches a three-point
     * invariant, is excluded with a limitation (mirroring the forms-of-loss path)
     * so a mismatched amount is never silently surfaced as the envelope currency.
     * An unrecognized {@code loss_form} (an O-RT FAIR form of loss) is surfaced as
     * a {@code null} loss form rather than rejecting the entry.
     */
    private static List<FairQuantitativeAnalysisResult.StakeholderSecondaryLoss> parseStakeholderSecondaryLosses(
            List<Object> entries, String currency, List<String> limitations) {
        List<FairQuantitativeAnalysisResult.StakeholderSecondaryLoss> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (Object raw : entries) {
            FairQuantitativeAnalysisResult.StakeholderSecondaryLoss loss =
                    parseStakeholderEntry(asMap(raw), currency, limitations);
            if (loss != null) {
                out.add(loss);
            }
        }
        return out;
    }

    /** Parses one stakeholder secondary-loss entry, or {@code null} when absent/mismatched/invalid. */
    private static FairQuantitativeAnalysisResult.StakeholderSecondaryLoss parseStakeholderEntry(
            Map<String, Object> entry, String currency, List<String> limitations) {
        if (entry == null) {
            return null;
        }
        String stakeholder = entry.get(KEY_STAKEHOLDER) == null ? null : String.valueOf(entry.get(KEY_STAKEHOLDER));
        FairFormOfLoss lossForm = FairFormOfLoss.fromJsonKey(
                entry.get(KEY_LOSS_FORM) == null ? null : String.valueOf(entry.get(KEY_LOSS_FORM)));
        String label = KEY_SECONDARY_BY_STAKEHOLDER + "[" + (stakeholder == null ? "?" : stakeholder) + "]";
        if (entry.containsKey(KEY_CURRENCY)) {
            String entryCurrency = String.valueOf(entry.get(KEY_CURRENCY));
            if (!entryCurrency.equals(currency)) {
                limitations.add(label + " uses currency " + entryCurrency + " but assessment currency is " + currency
                        + " — excluded from stakeholder materiality");
                return null;
            }
        }
        if (!validateThreePointFactor(label, entry, null, null, limitations)) {
            return null;
        }
        FairQuantitativeAnalysisResult.ThreePoint tp = parseThreePoint(entry);
        return tp == null
                ? null
                : new FairQuantitativeAnalysisResult.StakeholderSecondaryLoss(stakeholder, lossForm, tp);
    }
}
