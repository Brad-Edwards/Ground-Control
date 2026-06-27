package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Record a gate decision. The deciding actor is taken from the authenticated
 * server context, not the request body (ADR-026).
 */
public record GateDecisionRequest(
        @NotNull ResearchGatePoint gatePoint,
        @NotNull ResearchGateDecisionOutcome outcome,
        @Size(max = 200) String selectedOptionId,
        @Size(max = 1000) String rationaleSummary) {

    public GateDecisionCommand toCommand() {
        return new GateDecisionCommand(gatePoint, outcome, selectedOptionId, rationaleSummary);
    }
}
