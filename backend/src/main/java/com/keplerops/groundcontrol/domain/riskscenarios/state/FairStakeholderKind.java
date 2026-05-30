package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * Stakeholder bearing the (typically secondary) loss in a FAIR analysis per
 * GC-T016. FAIR secondary loss is by definition the reaction of stakeholders
 * to the primary loss event; FAIR-MAM extends this with the explicit
 * stakeholder dimension so executive decision support can answer "who is
 * paying for the loss, and how much?"
 *
 * <p>Declaration order matches the API DTO and MCP {@code FAIR_STAKEHOLDER_KINDS}
 * mirror per ADR-034.
 */
public enum FairStakeholderKind {
    ORGANIZATION,
    CUSTOMERS,
    REGULATORS,
    EMPLOYEES,
    INVESTORS,
    PARTNERS,
    PUBLIC
}
