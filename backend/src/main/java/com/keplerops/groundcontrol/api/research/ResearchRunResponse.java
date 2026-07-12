package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of a {@link ResearchRun}; bounded fields only. Includes the run's snapshotted high-risk operation policy. */
public record ResearchRunResponse(
        UUID id,
        String projectIdentifier,
        String uid,
        ResearchRunStage currentStage,
        ResearchRunStatus status,
        AutonomyLevel autonomyLevel,
        IntendedOutput intendedOutput,
        String ownerActor,
        List<String> allowedTools,
        String privacyConstraints,
        List<ResearchEgressAllowance> egressPolicy,
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
                List.copyOf(run.getAllowedTools()),
                run.getPrivacyConstraints(),
                List.copyOf(run.getEgressPolicy()),
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
