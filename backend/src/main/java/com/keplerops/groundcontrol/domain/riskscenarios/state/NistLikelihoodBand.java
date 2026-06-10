package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * NIST SP 800-30 Rev. 1 ordinal likelihood band. Used for likelihood of
 * initiation/occurrence (Table G-2), likelihood of adverse impact (Table G-3),
 * and overall likelihood (Table G-5). Ordinal only — must not be normalized
 * into a cross-methodology numeric score without an explicit method label and
 * conversion rule (per ADR-035 and the GC-T014 preflight note).
 */
public enum NistLikelihoodBand {
    VERY_LOW,
    LOW,
    MODERATE,
    HIGH,
    VERY_HIGH
}
