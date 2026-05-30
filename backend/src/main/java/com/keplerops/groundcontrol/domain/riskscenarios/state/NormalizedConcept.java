package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * Normalized vocabulary of risk concepts for cross-methodology terminology
 * crosswalk (GC-T012). Declaration order matches the GC-T012 requirement
 * statement and the mirrored MCP/frontend constant arrays (ADR-034).
 *
 * <p>{@link #IMPACT_OR_LOSS_MAGNITUDE} is the generic, methodology-agnostic
 * concept; FAIR-aligned methodologies (GC-T016) MUST further discriminate via
 * {@link #PRIMARY_LOSS_MAGNITUDE} (direct loss from the loss event) and
 * {@link #SECONDARY_LOSS_MAGNITUDE} (stakeholder-reaction loss). Non-FAIR
 * methodologies continue to use the generic concept verbatim.
 */
public enum NormalizedConcept {
    THREAT_SOURCE,
    THREAT_EVENT,
    VULNERABILITY_OR_EXPOSURE,
    ASSET,
    PROCESS_OR_OBJECTIVE,
    CONSEQUENCE_OR_EFFECT,
    CONTROL,
    LIKELIHOOD_OR_FREQUENCY,
    IMPACT_OR_LOSS_MAGNITUDE,
    PRIMARY_LOSS_MAGNITUDE,
    SECONDARY_LOSS_MAGNITUDE,
    TREATMENT
}
