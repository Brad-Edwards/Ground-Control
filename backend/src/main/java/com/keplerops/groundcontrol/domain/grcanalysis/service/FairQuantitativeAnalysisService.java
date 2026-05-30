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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAIR-aligned quantitative risk analysis per GC-T011.
 *
 * <p>Reads {@link RiskAssessmentResult} rows whose {@link MethodologyProfile}
 * family is {@link MethodologyFamily#FAIR}, decodes FAIR factors (TEF, contact
 * frequency, probability of action, vulnerability, susceptibility, threat
 * capability, resistance strength, primary loss magnitude, secondary loss
 * magnitude) from the methodology-defined input map, derives loss event
 * frequency, loss magnitude, and annualized loss expectancy via a deterministic
 * seeded Monte Carlo simulation (so reproducibility is auditable), and emits
 * percentile envelopes (p5/p10/p50/p90/p95/p99) on each output.
 *
 * <p>The service is read-only — it never mutates the assessment row. Monetary
 * outputs always carry an explicit {@code currency} (ISO-4217, default
 * {@code "USD"}) and {@code scale} ({@code "UNITS"} / {@code "THOUSANDS"} /
 * {@code "MILLIONS"}) so downstream consumers cannot silently mix currencies.
 *
 * <p>The Monte Carlo runs against {@link Random} seeded from a caller-supplied
 * value (default: stable hash of the assessment id) so two runs on identical
 * inputs produce identical percentile outputs.
 */
@Service
@Transactional(readOnly = true)
public class FairQuantitativeAnalysisService {

    static final String ANALYSIS_KIND = "fair_analysis";
    static final String DERIVATION_METHOD = "fair-v3.0-monte-carlo-pert-v1";
    static final String SCALE_CONTINUOUS = "continuous";
    static final String UNITS_MONETARY = "monetary per year";
    static final String DEFAULT_CURRENCY = "USD";
    static final String DEFAULT_SCALE = "UNITS";
    static final int DEFAULT_ITERATIONS = 10_000;
    static final int MAX_ITERATIONS = 1_000_000;
    static final int MIN_ITERATIONS = 100;

    // Reusable message fragments used in limitation notes.
    private static final String SECONDARY_DROPPED_SUFFIX = "; secondary contribution dropped from ALE / LM rollup";
    private static final String PER_LOSS_EVENT_UNIT = " per loss event";
    private static final String FAIR_INPUT_PREFIX = "FAIR input \"";

    // Methodology-defined input map keys (FAIR vocabulary).
    private static final String KEY_TEF = "threat_event_frequency";
    private static final String KEY_CONTACT_FREQUENCY = "contact_frequency";
    private static final String KEY_PROBABILITY_OF_ACTION = "probability_of_action";
    private static final String KEY_VULNERABILITY = "vulnerability";
    private static final String KEY_SUSCEPTIBILITY = "susceptibility";
    private static final String KEY_THREAT_CAPABILITY = "threat_capability";
    private static final String KEY_RESISTANCE_STRENGTH = "resistance_strength";
    private static final String KEY_LEF = "loss_event_frequency";
    private static final String KEY_PRIMARY_LOSS = "primary_loss_magnitude";
    private static final String KEY_SECONDARY_LEF = "secondary_loss_event_frequency";
    private static final String KEY_SECONDARY_LOSS = "secondary_loss_magnitude";
    private static final String KEY_FAIR_CAM = "fair_cam";
    private static final String KEY_SIMULATION = "simulation";

    private static final String KEY_LOW = "low";
    private static final String KEY_LIKELY = "likely";
    private static final String KEY_HIGH = "high";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_SEED = "seed";

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

        List<FairQuantitativeAnalysisResult.FairAnalysisItem> items = new ArrayList<>();
        int withSimulation = 0;
        int withLimitations = 0;
        for (RiskAssessmentResult row : rows) {
            if (row.getMethodologyProfile() == null
                    || row.getMethodologyProfile().getFamily() != MethodologyFamily.FAIR) {
                continue;
            }
            var item = toItem(row);
            items.add(item);
            if (item.outputs().annualizedLossExpectancy().percentiles() != null) {
                withSimulation++;
            }
            if (!item.limitations().isEmpty()) {
                withLimitations++;
            }
        }

        var counts = new FairQuantitativeAnalysisResult.Counts(items.size(), withSimulation, withLimitations);
        return new FairQuantitativeAnalysisResult(
                ANALYSIS_KIND,
                projectIdentifier,
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE_CONTINUOUS,
                UNITS_MONETARY,
                DEFAULT_CURRENCY,
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

    // -------------------------------------------------------------------------
    // toItem phases: input → secondary reconciliation → simulation → output
    // -------------------------------------------------------------------------

    private FairQuantitativeAnalysisResult.FairAnalysisItem toItem(RiskAssessmentResult row) {
        Map<String, Object> inputs = row.getInputFactors() == null ? Map.of() : row.getInputFactors();
        List<String> limitations = new ArrayList<>();

        PrimaryInputs primary = decodePrimaryInputs(inputs, limitations);
        SecondaryInputs secondary = decodeSecondaryInputs(inputs, primary, limitations);
        SimulationConfig sim = decodeSimulation(inputs, row.getId(), limitations);
        DerivedOutputs derived = runSimulationOrFallback(primary, secondary, sim, limitations);
        FairQuantitativeAnalysisResult.FairInputs typedInputs = buildTypedInputs(inputs, sim);

        return buildItem(row, typedInputs, derived.outputs(), limitations);
    }

    /** Phase 1: decode the three required FAIR input three-points and monetary metadata. */
    private PrimaryInputs decodePrimaryInputs(Map<String, Object> inputs, List<String> limitations) {
        ThreePoint tef = decodeThreePoint(inputs, KEY_TEF, limitations);
        ThreePoint vulnerability = decodeThreePoint(inputs, KEY_VULNERABILITY, limitations);
        ThreePoint primaryLoss = decodeThreePoint(inputs, KEY_PRIMARY_LOSS, limitations);

        Map<String, Object> primaryLossMap = asMap(inputs.get(KEY_PRIMARY_LOSS));
        String currency = stringValue(primaryLossMap.get(KEY_CURRENCY));
        if (currency == null || currency.isBlank()) {
            currency = DEFAULT_CURRENCY;
        }
        String monetaryScale = stringValue(primaryLossMap.get(KEY_SCALE));
        if (monetaryScale == null || monetaryScale.isBlank()) {
            monetaryScale = DEFAULT_SCALE;
        }
        return new PrimaryInputs(tef, vulnerability, primaryLoss, currency, monetaryScale);
    }

    /**
     * Phase 2: decode the optional secondary loss pair and reconcile currency / scale with the
     * primary envelope. Asymmetric pairs (only one side supplied) are dropped with a limitation.
     * Currency mismatches are dropped. Scale mismatches are normalized with a factor recorded as a
     * limitation.
     */
    private SecondaryInputs decodeSecondaryInputs(
            Map<String, Object> inputs, PrimaryInputs primary, List<String> limitations) {
        boolean secondaryFreqProvided = !asMap(inputs.get(KEY_SECONDARY_LEF)).isEmpty();
        boolean secondaryLossProvided = !asMap(inputs.get(KEY_SECONDARY_LOSS)).isEmpty();

        if (secondaryFreqProvided ^ secondaryLossProvided) {
            // Asymmetric secondary pair: the FAIR primary/secondary split is meaningful
            // only when both the secondary frequency and secondary magnitude are
            // supplied. If exactly one side is present, the other side silently
            // zeroes out the contribution to ALE — fail loud per the L0 posture and
            // drop the half-input from the simulation so the limitation is the
            // only signal the analyst sees. When both sides are absent, we say
            // nothing (secondary loss is optional in FAIR by design).
            String present = secondaryFreqProvided ? KEY_SECONDARY_LEF : KEY_SECONDARY_LOSS;
            String missing = secondaryFreqProvided ? KEY_SECONDARY_LOSS : KEY_SECONDARY_LEF;
            limitations.add("FAIR secondary loss pair incomplete: \"" + present + "\" present without \"" + missing
                    + SECONDARY_DROPPED_SUFFIX);
            return SecondaryInputs.dropped(1.0);
        }

        ThreePoint secondaryLossFreq =
                secondaryFreqProvided ? decodeThreePoint(inputs, KEY_SECONDARY_LEF, limitations) : null;
        ThreePoint secondaryLoss =
                secondaryLossProvided ? decodeThreePoint(inputs, KEY_SECONDARY_LOSS, limitations) : null;

        if (secondaryLoss == null) {
            return SecondaryInputs.dropped(1.0);
        }

        // Resolve secondary currency/scale and reconcile with the primary
        // envelope. Mixing currencies silently would produce a wrong ALE; mixing
        // scales is recoverable via a known factor, but we still record the
        // normalization. Refuse to combine across currencies — drop the
        // secondary contribution from the LM / ALE rollup and emit a limitation.
        Map<String, Object> secondaryLossMap = asMap(inputs.get(KEY_SECONDARY_LOSS));
        String secondaryCurrency = stringValue(secondaryLossMap.get(KEY_CURRENCY));
        if (secondaryCurrency == null || secondaryCurrency.isBlank()) {
            secondaryCurrency = primary.currency();
        }
        String secondaryScale = stringValue(secondaryLossMap.get(KEY_SCALE));
        if (secondaryScale == null || secondaryScale.isBlank()) {
            secondaryScale = primary.monetaryScale();
        }

        if (!secondaryCurrency.equalsIgnoreCase(primary.currency())) {
            limitations.add("FAIR secondary_loss_magnitude currency \"" + secondaryCurrency
                    + "\" differs from primary_loss_magnitude currency \"" + primary.currency()
                    + SECONDARY_DROPPED_SUFFIX);
            return SecondaryInputs.dropped(1.0);
        }

        if (!secondaryScale.equalsIgnoreCase(primary.monetaryScale())) {
            Double factor = scaleConversionFactor(secondaryScale, primary.monetaryScale());
            if (factor == null) {
                limitations.add("FAIR secondary_loss_magnitude scale \"" + secondaryScale
                        + "\" not convertible to primary_loss_magnitude scale \"" + primary.monetaryScale()
                        + SECONDARY_DROPPED_SUFFIX);
                return SecondaryInputs.dropped(1.0);
            }
            limitations.add("FAIR secondary_loss_magnitude scale \"" + secondaryScale
                    + "\" normalized to primary scale \"" + primary.monetaryScale() + "\" (factor=" + factor + ")");
            return new SecondaryInputs(secondaryLossFreq, secondaryLoss, factor);
        }

        return new SecondaryInputs(secondaryLossFreq, secondaryLoss, 1.0);
    }

    /** Phase 3: run the Monte Carlo simulation, or fall back to zero outputs if inputs incomplete. */
    private DerivedOutputs runSimulationOrFallback(
            PrimaryInputs primary, SecondaryInputs secondary, SimulationConfig sim, List<String> limitations) {
        if (primary.tef() != null && primary.vulnerability() != null && primary.primaryLoss() != null) {
            return simulate(new FairCalibrationInputs(primary, secondary, sim));
        }
        return unsupportedOutputs(primary.currency(), primary.monetaryScale(), limitations);
    }

    /** Phase 4: assemble the typed FairInputs record for the result payload. */
    private static FairQuantitativeAnalysisResult.FairInputs buildTypedInputs(
            Map<String, Object> inputs, SimulationConfig sim) {
        return new FairQuantitativeAnalysisResult.FairInputs(
                asMap(inputs.get(KEY_TEF)),
                asMap(inputs.get(KEY_CONTACT_FREQUENCY)),
                asMap(inputs.get(KEY_PROBABILITY_OF_ACTION)),
                asMap(inputs.get(KEY_VULNERABILITY)),
                asMap(inputs.get(KEY_SUSCEPTIBILITY)),
                asMap(inputs.get(KEY_THREAT_CAPABILITY)),
                asMap(inputs.get(KEY_RESISTANCE_STRENGTH)),
                asMap(inputs.get(KEY_LEF)),
                asMap(inputs.get(KEY_PRIMARY_LOSS)),
                asMap(inputs.get(KEY_SECONDARY_LEF)),
                asMap(inputs.get(KEY_SECONDARY_LOSS)),
                asMap(inputs.get(KEY_FAIR_CAM)),
                new FairQuantitativeAnalysisResult.SimulationInputs(sim.iterations(), sim.seed()));
    }

    /** Phase 5: build the final FairAnalysisItem from the assembled parts. */
    private static FairQuantitativeAnalysisResult.FairAnalysisItem buildItem(
            RiskAssessmentResult row,
            FairQuantitativeAnalysisResult.FairInputs typedInputs,
            FairQuantitativeAnalysisResult.FairOutputs outputs,
            List<String> limitations) {
        MethodologyProfile profile = row.getMethodologyProfile();
        return new FairQuantitativeAnalysisResult.FairAnalysisItem(
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
                outputs,
                row.getEvidenceRefs() == null ? List.of() : List.copyOf(row.getEvidenceRefs()),
                List.copyOf(limitations));
    }

    private DerivedOutputs unsupportedOutputs(String currency, String monetaryScale, List<String> limitations) {
        limitations.add("FAIR factors missing — ALE / LEF / LM cannot be derived");
        var zeroFreq = new FairQuantitativeAnalysisResult.FrequencyEnvelope(0, 0, 0, "events per year", null);
        var zeroMonetary = new FairQuantitativeAnalysisResult.MonetaryEnvelope(
                0, 0, 0, currency, monetaryScale, currency + PER_LOSS_EVENT_UNIT, null);
        var zeroAle = new FairQuantitativeAnalysisResult.MonetaryEnvelope(
                0, 0, 0, currency, monetaryScale, currency + " per year", null);
        var outputs = new FairQuantitativeAnalysisResult.FairOutputs(
                zeroAle, zeroFreq, zeroMonetary, zeroMonetary, zeroMonetary, "not-derivable (missing FAIR factors)");
        return new DerivedOutputs(outputs);
    }

    private SimulationConfig decodeSimulation(Map<String, Object> inputs, UUID assessmentId, List<String> limitations) {
        Map<String, Object> sim = asMap(inputs.get(KEY_SIMULATION));
        int iterations = DEFAULT_ITERATIONS;
        Long seed = null;
        if (sim.containsKey(KEY_ITERATIONS)) {
            Double raw = asDouble(sim.get(KEY_ITERATIONS));
            if (raw != null) {
                int requested = raw.intValue();
                iterations = clampIterations(requested, limitations);
            }
        }
        if (sim.containsKey(KEY_SEED)) {
            Double raw = asDouble(sim.get(KEY_SEED));
            if (raw != null) {
                seed = raw.longValue();
            }
        }
        long effectiveSeed = seed != null ? seed : stableSeed(assessmentId);
        return new SimulationConfig(iterations, effectiveSeed);
    }

    private static int clampIterations(int requested, List<String> limitations) {
        if (requested < MIN_ITERATIONS) {
            limitations.add("simulation.iterations=" + requested + " below minimum " + MIN_ITERATIONS + "; using "
                    + MIN_ITERATIONS);
            return MIN_ITERATIONS;
        }
        if (requested > MAX_ITERATIONS) {
            limitations.add("simulation.iterations=" + requested + " above maximum " + MAX_ITERATIONS + "; clamped to "
                    + MAX_ITERATIONS);
            return MAX_ITERATIONS;
        }
        return requested;
    }

    private static long stableSeed(UUID id) {
        // Deterministic seed when the analyst does not supply one: derive from
        // the assessment UUID so two runs of the same row reproduce the same
        // percentile distribution. The seed is reported back in the result so
        // a reviewer can audit reproducibility (GC-T011).
        return id == null ? 0L : id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }

    /**
     * Groups all inputs needed for a Monte Carlo simulation run. Introduced to
     * keep {@link #simulate} below the 7-parameter Sonar limit while preserving
     * the named-field clarity required for auditability (GC-T011).
     */
    private record FairCalibrationInputs(PrimaryInputs primary, SecondaryInputs secondary, SimulationConfig sim) {}

    private DerivedOutputs simulate(FairCalibrationInputs cal) {
        ThreePoint tef = cal.primary().tef();
        ThreePoint vulnerability = cal.primary().vulnerability();
        ThreePoint primaryLoss = cal.primary().primaryLoss();
        ThreePoint secondaryLossFreq = cal.secondary().lossFreq();
        ThreePoint secondaryLoss = cal.secondary().loss();
        double secondaryToPrimaryScaleFactor = cal.secondary().scaleFactor();
        SimulationConfig sim = cal.sim();
        String currency = cal.primary().currency();
        String monetaryScale = cal.primary().monetaryScale();

        Random rng = new Random(sim.seed());
        double[] lefSamples = new double[sim.iterations()];
        double[] lmSamples = new double[sim.iterations()];
        double[] aleSamples = new double[sim.iterations()];
        double[] primarySamples = new double[sim.iterations()];
        double[] secondarySamples = new double[sim.iterations()];

        for (int i = 0; i < sim.iterations(); i++) {
            double tefSample = sampleTriangular(rng, tef);
            double vulnSample = clamp01(sampleTriangular(rng, vulnerability));
            double lef = tefSample * vulnSample;

            double primarySample = sampleTriangular(rng, primaryLoss);
            double secondaryFreqSample =
                    secondaryLossFreq == null ? 0.0 : clamp01(sampleTriangular(rng, secondaryLossFreq));
            double secondarySampleRaw = secondaryLoss == null ? 0.0 : sampleTriangular(rng, secondaryLoss);
            // Normalize secondary magnitude into the primary scale before
            // combining so the LM rollup never silently mixes scales.
            double secondarySample = secondarySampleRaw * secondaryToPrimaryScaleFactor;
            double secondaryContribution = secondarySample * secondaryFreqSample;
            double lm = primarySample + secondaryContribution;

            lefSamples[i] = lef;
            lmSamples[i] = lm;
            aleSamples[i] = lef * lm;
            primarySamples[i] = primarySample;
            secondarySamples[i] = secondaryContribution;
        }

        var aleEnvelope = monetaryEnvelope(aleSamples, currency, monetaryScale, currency + " per year");
        var lefEnvelope = frequencyEnvelope(lefSamples);
        var lmEnvelope = monetaryEnvelope(lmSamples, currency, monetaryScale, currency + PER_LOSS_EVENT_UNIT);
        var primaryEnvelope = monetaryEnvelope(primarySamples, currency, monetaryScale, currency + PER_LOSS_EVENT_UNIT);
        var secondaryEnvelope =
                monetaryEnvelope(secondarySamples, currency, monetaryScale, currency + PER_LOSS_EVENT_UNIT);

        String derivation = "monte-carlo: ALE = LEF * LM; LEF = TEF * Vulnerability; "
                + "LM = PrimaryLoss + (SecondaryLEF * SecondaryLoss); seed=" + sim.seed()
                + "; iterations=" + sim.iterations();
        var outputs = new FairQuantitativeAnalysisResult.FairOutputs(
                aleEnvelope, lefEnvelope, lmEnvelope, primaryEnvelope, secondaryEnvelope, derivation);
        return new DerivedOutputs(outputs);
    }

    private static double sampleTriangular(Random rng, ThreePoint p) {
        double low = p.low();
        double likely = p.likely();
        double high = p.high();
        if (high <= low) {
            return likely;
        }
        double u = rng.nextDouble();
        double cutoff = (likely - low) / (high - low);
        if (u < cutoff) {
            return low + Math.sqrt(u * (high - low) * (likely - low));
        }
        return high - Math.sqrt((1 - u) * (high - low) * (high - likely));
    }

    private FairQuantitativeAnalysisResult.MonetaryEnvelope monetaryEnvelope(
            double[] samples, String currency, String scale, String units) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        var pct = percentilesOf(sorted);
        double low = sorted[0];
        double high = sorted[sorted.length - 1];
        double mean = mean(sorted);
        return new FairQuantitativeAnalysisResult.MonetaryEnvelope(low, mean, high, currency, scale, units, pct);
    }

    private FairQuantitativeAnalysisResult.FrequencyEnvelope frequencyEnvelope(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        var pct = percentilesOf(sorted);
        return new FairQuantitativeAnalysisResult.FrequencyEnvelope(
                sorted[0], mean(sorted), sorted[sorted.length - 1], "events per year", pct);
    }

    private static FairQuantitativeAnalysisResult.Percentiles percentilesOf(double[] sorted) {
        return new FairQuantitativeAnalysisResult.Percentiles(
                quantile(sorted, 0.05),
                quantile(sorted, 0.10),
                quantile(sorted, 0.50),
                quantile(sorted, 0.90),
                quantile(sorted, 0.95),
                quantile(sorted, 0.99));
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int idx = (int) Math.floor(q * (sorted.length - 1));
        return sorted[Math.clamp(idx, 0, sorted.length - 1)];
    }

    private static double mean(double[] s) {
        double sum = 0;
        for (double v : s) {
            sum += v;
        }
        return s.length == 0 ? 0 : sum / s.length;
    }

    private static double clamp01(double v) {
        return Math.clamp(v, 0.0, 1.0);
    }

    private static ThreePoint decodeThreePoint(Map<String, Object> inputs, String key, List<String> limitations) {
        Map<String, Object> m = asMap(inputs.get(key));
        if (m.isEmpty()) {
            if (limitations != null) {
                limitations.add(FAIR_INPUT_PREFIX + key + "\" missing");
            }
            return null;
        }
        Double low = asDouble(m.get(KEY_LOW));
        Double likely = asDouble(m.get(KEY_LIKELY));
        Double high = asDouble(m.get(KEY_HIGH));
        if (low == null || likely == null || high == null) {
            if (limitations != null) {
                limitations.add(FAIR_INPUT_PREFIX + key + "\" missing low/likely/high");
            }
            return null;
        }
        if (likely < low || high < likely) {
            if (limitations != null) {
                limitations.add(FAIR_INPUT_PREFIX + key + "\" violates low <= likely <= high; using clamped order");
            }
            double newLow = Math.min(low, Math.min(likely, high));
            double newHigh = Math.max(low, Math.max(likely, high));
            double newLikely = Math.clamp(likely, newLow, newHigh);
            return new ThreePoint(newLow, newLikely, newHigh);
        }
        return new ThreePoint(low, likely, high);
    }

    private static Double asDouble(Object o) {
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

    private static String stringValue(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Convert a monetary scale label ({@code UNITS} / {@code THOUSANDS} /
     * {@code MILLIONS}) to its multiplier against {@code UNITS}, or {@code null}
     * if the label is unrecognized.
     */
    private static Double scaleMultiplier(String scale) {
        if (scale == null) {
            return null;
        }
        return switch (scale.toUpperCase(Locale.ROOT)) {
            case DEFAULT_SCALE -> 1.0;
            case "THOUSANDS" -> 1_000.0;
            case "MILLIONS" -> 1_000_000.0;
            default -> null;
        };
    }

    /**
     * Factor to multiply a value expressed in {@code from} scale to convert it
     * into {@code to} scale. Returns {@code null} if either label is unknown so
     * the caller can fail loud rather than silently mixing magnitudes.
     */
    private static Double scaleConversionFactor(String from, String to) {
        Double fromMul = scaleMultiplier(from);
        Double toMul = scaleMultiplier(to);
        if (fromMul == null || toMul == null) {
            return null;
        }
        return fromMul / toMul;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? Collections.unmodifiableMap((Map<String, Object>) m) : Map.of();
    }

    private record ThreePoint(double low, double likely, double high) {}

    private record SimulationConfig(int iterations, long seed) {}

    private record DerivedOutputs(FairQuantitativeAnalysisResult.FairOutputs outputs) {}

    /** Decoded primary FAIR inputs plus monetary metadata for the simulation. */
    private record PrimaryInputs(
            ThreePoint tef, ThreePoint vulnerability, ThreePoint primaryLoss, String currency, String monetaryScale) {}

    /**
     * Decoded (and currency/scale-reconciled) secondary FAIR loss pair.
     * When the pair is dropped (asymmetric, currency mismatch, or absent), both
     * {@code lossFreq} and {@code loss} are {@code null} and {@code scaleFactor}
     * is {@code 1.0}.
     */
    private record SecondaryInputs(ThreePoint lossFreq, ThreePoint loss, double scaleFactor) {
        static SecondaryInputs dropped(double scaleFactor) {
            return new SecondaryInputs(null, null, scaleFactor);
        }
    }
}
