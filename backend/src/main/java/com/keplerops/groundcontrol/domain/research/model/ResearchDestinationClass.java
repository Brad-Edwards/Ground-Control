package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N006 / ADR-084 §2 — bounded destination class for a research egress
 * decision. {@code LOCAL} is the default-permitted destination; every other
 * value is an external service that a run's snapshotted egress policy must
 * explicitly allow (for a given {@link ResearchDataClass} and
 * {@link ResearchDataForm}) before private material may reach it.
 */
public enum ResearchDestinationClass {
    LOCAL,
    AI_PROVIDER,
    CITATION_PROVIDER,
    VERSION_CONTROL,
    REFERENCE_MANAGER,
    BROWSER_TARGET,
    EXTERNAL_STORAGE,
    LAB_HARDWARE,
    OTHER_EXTERNAL
}
