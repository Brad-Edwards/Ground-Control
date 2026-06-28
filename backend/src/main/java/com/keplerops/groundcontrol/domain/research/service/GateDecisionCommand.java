package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.GateRecommendationProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;

/**
 * Record a durable decision for a run-scoped gate (GC-RSCH-R003 / GC-RSCH-F004,
 * ADR-066). {@code rationaleSummary} and {@code recommendationSummary} are
 * bounded summaries only — never raw prompts, manuscript prose, or full
 * recommendations. The deciding actor is taken from the authenticated server
 * context (ADR-026), not this command, so a gate decision cannot be attributed
 * to a forged identity. The recommendation fields, {@code questionKey}, and
 * {@code sourceActionId} are optional and captured on the append-only decision
 * log alongside the decision.
 */
public record GateDecisionCommand(
        ResearchGatePoint gatePoint,
        ResearchGateDecisionOutcome outcome,
        String selectedOptionId,
        String rationaleSummary,
        String recommendationOptionId,
        String recommendationSummary,
        GateRecommendationProvenance recommendationProvenance,
        String questionKey,
        String sourceActionId) {}
