package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F004 / ADR-066 — origin of a gate recommendation captured on a {@link
 * ResearchRunGateDecisionLog} row. Distinguishes an agent-produced option from a
 * system-policy default and from a human reviewer's recommendation.
 */
public enum GateRecommendationProvenance {
    AGENT,
    SYSTEM_POLICY,
    HUMAN_REVIEWER
}
