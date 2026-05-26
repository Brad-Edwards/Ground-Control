package com.keplerops.groundcontrol.domain.research.model;

/**
 * How autonomously the research run executes. COPILOT gates more user
 * decisions; AUTONOMOUS proceeds with declared defaults unless a gate is
 * marked mandatory. Matches the user-gate vocabulary in the lit-review
 * skills. See ADR-056.
 */
public enum AutonomyLevel {
    COPILOT,
    AUTONOMOUS
}
