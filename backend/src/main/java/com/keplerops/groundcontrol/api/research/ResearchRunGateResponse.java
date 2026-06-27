package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchRunGate} policy/decision row. */
public record ResearchRunGateResponse(
        UUID id,
        ResearchGatePoint gatePoint,
        ResearchRunStage guardedStageExit,
        ResearchGateBehavior behavior,
        ResearchGateStatus status,
        ResearchGateDecisionOutcome decisionOutcome,
        String selectedOptionId,
        String rationaleSummary,
        String policyBasis,
        String resolvedByActor,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunGateResponse from(ResearchRunGate g) {
        return new ResearchRunGateResponse(
                g.getId(),
                g.getGatePoint(),
                g.getGatePoint().guardedStageExit(),
                g.getBehavior(),
                g.getStatus(),
                g.getDecisionOutcome(),
                g.getSelectedOptionId(),
                g.getRationaleSummary(),
                g.getPolicyBasis(),
                g.getResolvedByActor(),
                g.getCreatedAt(),
                g.getUpdatedAt());
    }
}
