package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R003 / ADR-063 — resolved per-run behavior of a {@link
 * ResearchGatePoint}. {@code COPILOT} runs default every gate to
 * {@code REQUIRE_HUMAN}; {@code AUTONOMOUS} runs default to
 * {@code AUTONOMOUS_DEFAULT} (declared default is auto-used, but the decision is
 * still recorded). {@code DISABLED} skips the gate for the run.
 */
public enum ResearchGateBehavior {
    REQUIRE_HUMAN,
    AUTONOMOUS_DEFAULT,
    DISABLED
}
