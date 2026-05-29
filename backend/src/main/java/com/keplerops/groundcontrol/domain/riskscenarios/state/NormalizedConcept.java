package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * Normalized vocabulary of risk concepts for cross-methodology terminology
 * crosswalk (GC-T012). Declaration order matches the GC-T012 requirement
 * statement and the mirrored MCP/frontend constant arrays (ADR-034).
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
    TREATMENT
}
