package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.GateRecommendationProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Record a gate decision. The deciding actor is taken from the authenticated
 * server context, not the request body (ADR-026). The recommendation fields,
 * {@code questionKey}, and {@code sourceActionId} are optional and captured on
 * the append-only decision log alongside the decision (GC-RSCH-F004, ADR-066).
 */
public record GateDecisionRequest(
        @NotNull ResearchGatePoint gatePoint,
        @NotNull ResearchGateDecisionOutcome outcome,
        @Size(max = 200) String selectedOptionId,
        @Size(max = 1000) String rationaleSummary,
        @Size(max = 200) String recommendationOptionId,
        @Size(max = 1000) String recommendationSummary,
        GateRecommendationProvenance recommendationProvenance,
        @Size(max = 200) String questionKey,
        @Size(max = 200) String sourceActionId) {

    public GateDecisionCommand toCommand() {
        return new GateDecisionCommand(
                gatePoint,
                outcome,
                selectedOptionId,
                rationaleSummary,
                recommendationOptionId,
                recommendationSummary,
                recommendationProvenance,
                questionKey,
                sourceActionId);
    }
}
