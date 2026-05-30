package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * FAIR-MAM canonical loss-form taxonomy per GC-T016.
 *
 * <p>FAIR distinguishes the form a loss takes (the FAIR "primary loss forms")
 * from where in the loss chain it appears (primary vs. secondary). FAIR-MAM
 * (Materiality Assessment Model) augments the original five with four further
 * categories so executive financial quantification can attribute losses to
 * stakeholder-specific buckets. The nine-form set used by this codebase:
 * <ol>
 *   <li>{@link #PRODUCTIVITY} — lost employee/process output during the event.</li>
 *   <li>{@link #RESPONSE} — internal/external incident response cost.</li>
 *   <li>{@link #REPLACEMENT} — cost of replacing destroyed assets.</li>
 *   <li>{@link #COMPETITIVE_ADVANTAGE} — lost market position / IP value.</li>
 *   <li>{@link #FINES_AND_JUDGMENTS} — regulatory penalties and legal judgments.</li>
 *   <li>{@link #REPUTATION} — reputational/brand damage with downstream revenue loss.</li>
 *   <li>{@link #CUSTOMER_COMPENSATION} — direct customer credits, refunds, and SLA penalties (FAIR-MAM extension).</li>
 *   <li>{@link #NOTIFICATION_AND_CREDIT_MONITORING} — breach notification and credit-monitoring services (FAIR-MAM extension).</li>
 *   <li>{@link #BUSINESS_INTERRUPTION} — revenue lost while operations are partially or fully halted (FAIR-MAM extension).</li>
 * </ol>
 *
 * <p>Declaration order matches the API DTO and MCP {@code FAIR_LOSS_FORMS}
 * mirror per ADR-034. Each loss form can appear on a primary or secondary
 * loss line; the primary/secondary discrimination is carried by
 * {@link NormalizedConcept#PRIMARY_LOSS_MAGNITUDE} vs.
 * {@link NormalizedConcept#SECONDARY_LOSS_MAGNITUDE} on the crosswalk, not by
 * this enum.
 */
public enum FairLossForm {
    PRODUCTIVITY,
    RESPONSE,
    REPLACEMENT,
    COMPETITIVE_ADVANTAGE,
    FINES_AND_JUDGMENTS,
    REPUTATION,
    CUSTOMER_COMPENSATION,
    NOTIFICATION_AND_CREDIT_MONITORING,
    BUSINESS_INTERRUPTION
}
