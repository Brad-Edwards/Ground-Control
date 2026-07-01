package com.keplerops.groundcontrol.domain.threatenumeration.state;

/**
 * Semantic grouping of a threat rule within the enumeration engine. STRIDE_BASELINE rules
 * directly map STRIDE categories to element kinds; the remaining categories are cross-cutting
 * security concerns that fire on structural predicates (trust-boundary crossings, external
 * endpoints, metadata tags).
 */
public enum ThreatRuleCategory {
    STRIDE_BASELINE,
    DEPLOYMENT_PIPELINE,
    AUTHN_AUTHZ,
    SECRET_HANDLING,
    UNTRUSTED_INPUT,
    DATA_EGRESS,
    CRYPTO
}
