package com.keplerops.groundcontrol.api.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST output shape for ResearchIntake. Returned nested inside
 * {@code ProjectResponse} for RESEARCH projects and as the body of
 * {@code PUT /research-intake/{identifier}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResearchIntakeResponse(
        UUID id,
        String goal,
        String paperContext,
        ContributionType contributionType,
        IntendedOutput intendedOutput,
        AutonomyLevel autonomyLevel,
        List<String> allowedTools,
        String privacyConstraints,
        Long budgetTokens,
        Integer budgetWallClockMinutes,
        Long budgetCostUsdMicros,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchIntakeResponse from(ResearchIntake intake) {
        return new ResearchIntakeResponse(
                intake.getId(),
                intake.getGoal(),
                intake.getPaperContext(),
                intake.getContributionType(),
                intake.getIntendedOutput(),
                intake.getAutonomyLevel(),
                List.copyOf(intake.getAllowedTools()),
                intake.getPrivacyConstraints(),
                intake.getBudgetTokens(),
                intake.getBudgetWallClockMinutes(),
                intake.getBudgetCostUsdMicros(),
                intake.getCreatedAt(),
                intake.getUpdatedAt());
    }
}
