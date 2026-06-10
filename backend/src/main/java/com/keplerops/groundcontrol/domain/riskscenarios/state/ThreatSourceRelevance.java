package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * NIST SP 800-30 Rev. 1 Appendix D Table D-2 threat-source relevance bands.
 * Determines whether a threat source is plausible enough to drive the
 * assessment forward.
 */
public enum ThreatSourceRelevance {
    CONFIRMED,
    EXPECTED,
    ANTICIPATED,
    PREDICTED,
    POSSIBLE,
    NOT_APPLICABLE
}
