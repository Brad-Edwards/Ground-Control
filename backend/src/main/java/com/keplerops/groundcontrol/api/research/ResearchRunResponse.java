package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import java.time.Instant;
import java.util.UUID;

/** Read view of a {@link ResearchRun}; bounded fields only. */
public record ResearchRunResponse(
        UUID id,
        String projectIdentifier,
        String uid,
        ResearchRunStage currentStage,
        ResearchRunStatus status,
        AutonomyLevel autonomyLevel,
        IntendedOutput intendedOutput,
        String ownerActor,
        Long budgetTokens,
        Integer budgetWallClockMinutes,
        Long budgetCostUsdMicros,
        long observedTokens,
        long observedCostUsdMicros,
        Instant startedAt,
        Instant stoppedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunResponse from(ResearchRun run) {
        return new ResearchRunResponse(
                run.getId(),
                run.getProject().getIdentifier(),
                run.getUid(),
                run.getCurrentStage(),
                run.getStatus(),
                run.getAutonomyLevel(),
                run.getIntendedOutput(),
                run.getOwnerActor(),
                run.getBudgetTokens(),
                run.getBudgetWallClockMinutes(),
                run.getBudgetCostUsdMicros(),
                run.getObservedTokens(),
                run.getObservedCostUsdMicros(),
                run.getStartedAt(),
                run.getStoppedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
