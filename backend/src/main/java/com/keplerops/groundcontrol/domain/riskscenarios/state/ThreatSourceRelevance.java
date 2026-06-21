package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * NIST SP 800-30 Rev. 1 Appendix E Table E-4 threat-event relevance bands.
 *
 * <p>The type name is retained for compatibility with the original GC-T014
 * enum mirror. New contracts should expose these values as threat-event
 * relevance, not threat-source relevance.
 */
public enum ThreatSourceRelevance {
    CONFIRMED,
    EXPECTED,
    ANTICIPATED,
    PREDICTED,
    POSSIBLE,
    NOT_APPLICABLE
}
