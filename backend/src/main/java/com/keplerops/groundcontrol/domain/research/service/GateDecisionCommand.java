package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;

/**
 * Record a durable decision for a run-scoped gate (GC-RSCH-R003). {@code
 * rationaleSummary} is a bounded summary only — never raw prompts, manuscript
 * prose, or full recommendations. The deciding actor is taken from the
 * authenticated server context (ADR-026), not this command, so a gate decision
 * cannot be attributed to a forged identity.
 */
public record GateDecisionCommand(
        ResearchGatePoint gatePoint,
        ResearchGateDecisionOutcome outcome,
        String selectedOptionId,
        String rationaleSummary) {}
