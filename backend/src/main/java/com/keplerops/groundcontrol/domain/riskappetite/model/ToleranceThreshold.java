package com.keplerops.groundcontrol.domain.riskappetite.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A single tolerance ceiling within a {@link RiskAppetiteProfile} (GC-T005). Each threshold is a
 * methodology-appropriate <em>upper bound</em> on a residual risk metric: a residual value that
 * exceeds the ceiling breaches appetite and is flagged for escalation.
 *
 * <p>The threshold is keyed to one output field via {@code metricPath} (a dot path into a
 * {@code RiskAssessmentResult.computedOutputs} map, e.g. {@code annualized_loss_expectancy.likely}
 * for FAIR, {@code risk_value} for ISO, {@code risk_level} for NIST) and expresses its ceiling as
 * exactly one of:
 * <ul>
 *   <li><b>quantitative</b> — {@code maxQuantitativeValue} plus {@code units} (and {@code currency}
 *       for monetary loss). Covers monetary loss ranges, loss event frequency, and exceedance
 *       probability.</li>
 *   <li><b>ordinal</b> — {@code maxOrdinalValue} plus an ascending {@code orderedScale} that defines
 *       the severity ordering (e.g. {@code [VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH]}).</li>
 * </ul>
 *
 * <p>Exactly one of the two forms must be populated; the XOR and cross-field constraints (ordinal
 * value present in the scale, probability bounds, non-negative quantities) are enforced in
 * {@code RiskAppetiteProfileService}, since they cannot be expressed as per-field bean-validation
 * annotations. Instances are stored as a JSON list on the profile via
 * {@link com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.ToleranceThresholdListConverter}.
 *
 * @param riskCategory optional scope tag; when set, the threshold applies only to assessments whose
 *     risk register record carries this category tag. Null means it applies to every assessment.
 * @param metricPath dot path into {@code computedOutputs} identifying the residual metric.
 * @param maxQuantitativeValue inclusive upper bound for a numeric metric (breach when residual &gt;
 *     this value); null for ordinal thresholds.
 * @param units unit of the quantitative ceiling (e.g. {@code USD}, {@code events/year},
 *     {@code probability}); informational for ordinal thresholds.
 * @param currency ISO currency code when the metric is monetary; compared against the assessment's
 *     declared currency at evaluation time so USD is never silently compared to EUR.
 * @param maxOrdinalValue highest tolerated ordinal band; null for quantitative thresholds.
 * @param orderedScale ascending severity ordering used to compare ordinal values; required when
 *     {@code maxOrdinalValue} is set and must contain it.
 * @param label optional human-readable description of the tolerance statement.
 */
public record ToleranceThreshold(
        @Size(max = 100) String riskCategory,
        @NotBlank @Size(max = 200) String metricPath,
        Double maxQuantitativeValue,
        @Size(max = 50) String units,
        @Size(max = 10) String currency,
        @Size(max = 100) String maxOrdinalValue,
        List<@Size(max = 100) String> orderedScale,
        @Size(max = 200) String label) {}
