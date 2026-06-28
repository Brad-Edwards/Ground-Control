package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.GateRecommendationProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link ResearchRunGateDecisionLog} (GC-RSCH-F004, ADR-066).
 * DTOs (not the controller) name the domain enums (ArchUnit boundary).
 */
public record ResearchRunGateDecisionLogResponse(
        UUID id,
        ResearchGatePoint gatePoint,
        ResearchRunStage guardedStage,
        Integer artifactAttemptNo,
        String questionKey,
        String recommendationOptionId,
        String recommendationSummary,
        GateRecommendationProvenance recommendationProvenance,
        ResearchGateDecisionOutcome decisionOutcome,
        String selectedOptionId,
        String rationaleSummary,
        String policyBasis,
        String sourceActionId,
        String decisionActor,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunGateDecisionLogResponse from(ResearchRunGateDecisionLog log) {
        return new ResearchRunGateDecisionLogResponse(
                log.getId(),
                log.getGatePoint(),
                log.getGuardedStage(),
                log.getArtifactAttemptNo(),
                log.getQuestionKey(),
                log.getRecommendationOptionId(),
                log.getRecommendationSummary(),
                log.getRecommendationProvenance(),
                log.getDecisionOutcome(),
                log.getSelectedOptionId(),
                log.getRationaleSummary(),
                log.getPolicyBasis(),
                log.getSourceActionId(),
                log.getDecisionActor(),
                log.getDecidedAt(),
                log.getCreatedAt(),
                log.getUpdatedAt());
    }
}
