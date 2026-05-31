package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * GC-T005: Discriminator for the threshold shape carried by a single
 * {@code RiskAppetiteTolerance} band.
 *
 * <p>Methodology-appropriate semantics:
 * <ul>
 *   <li>{@link #QUALITATIVE} — ordinal-band criteria (e.g. NIST VERY_LOW..VERY_HIGH).
 *   <li>{@link #MONETARY_RANGE} — FAIR-style monetary tolerance (low/likely/high in currency units).
 *   <li>{@link #LOSS_EVENT_FREQUENCY} — annualized loss-event frequency cap (FAIR LEF threshold).
 *   <li>{@link #EXCEEDANCE_PROBABILITY} — probability of exceeding a stated loss magnitude.
 *   <li>{@link #COMPOSITE} — composite/organization-defined kind whose interpretation
 *       lives in the band's {@code criteria} map (escape hatch for hybrid methodologies).
 * </ul>
 */
public enum AppetiteToleranceKind {
    QUALITATIVE,
    MONETARY_RANGE,
    LOSS_EVENT_FREQUENCY,
    EXCEEDANCE_PROBABILITY,
    COMPOSITE
}
