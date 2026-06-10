package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * NIST SP 800-30 Rev. 1 Table H-3 ordinal impact band. Ordinal only — must
 * not be normalized into a cross-methodology numeric score without an explicit
 * method label and conversion rule (per ADR-035 and the GC-T014 preflight
 * note).
 */
public enum NistImpactBand {
    VERY_LOW,
    LOW,
    MODERATE,
    HIGH,
    VERY_HIGH
}
