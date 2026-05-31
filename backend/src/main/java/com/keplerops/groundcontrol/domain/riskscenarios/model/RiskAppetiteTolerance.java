package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.riskscenarios.state.AppetiteToleranceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

/**
 * GC-T005: single tolerance band inside a {@code RiskAppetiteProfile}.
 *
 * <p>A band carries a methodology-appropriate threshold:
 * <ul>
 *   <li>{@code kind = QUALITATIVE} — band identified by {@code qualitativeLabel}
 *       (e.g. {@code MODERATE}, {@code HIGH}) and the {@code criteria} map carries
 *       the methodology-specific decision rules.
 *   <li>{@code kind = MONETARY_RANGE} — {@code monetaryLow}/{@code monetaryHigh}
 *       in {@code currency} bound the band.
 *   <li>{@code kind = LOSS_EVENT_FREQUENCY} — {@code lossEventFrequencyMax}
 *       caps annual events.
 *   <li>{@code kind = EXCEEDANCE_PROBABILITY} — {@code exceedanceProbabilityMax}
 *       caps probability of exceeding {@code monetaryHigh}.
 *   <li>{@code kind = COMPOSITE} — fully custom; {@code criteria} carries
 *       organization-defined keys.
 * </ul>
 *
 * <p>{@code category} is an organization-defined risk category label
 * (e.g. {@code CYBER}, {@code OPERATIONAL}) so an appetite profile can carry
 * different tolerances per category.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskAppetiteTolerance(
        @NotBlank @Size(max = 100) String category,
        @NotNull AppetiteToleranceKind kind,
        @Size(max = 50) String qualitativeLabel,
        BigDecimal monetaryLow,
        BigDecimal monetaryHigh,
        @Size(max = 10) String currency,
        BigDecimal lossEventFrequencyMax,
        BigDecimal exceedanceProbabilityMax,
        Map<String, Object> criteria,
        @Size(max = 1000) String rationale) {}
