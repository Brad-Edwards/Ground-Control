package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * NIST SP 800-30 Rev. 1 threat-event kind. Adversarial events flow through the
 * threat-source capability/intent/targeting branch; non-adversarial events flow
 * through the range-of-effects / frequency branch and must not require
 * adversarial-only fields (per the GC-T014 preflight note, lines 173-175).
 */
public enum ThreatEventKind {
    ADVERSARIAL,
    NON_ADVERSARIAL
}
