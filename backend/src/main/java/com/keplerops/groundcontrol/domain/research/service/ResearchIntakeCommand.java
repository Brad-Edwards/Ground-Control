package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import java.util.List;

/**
 * Service-layer command for creating or fully replacing a ResearchIntake.
 * All fields are required when present at the service boundary; the API layer
 * normalises optional vs required per field, and the Bean Validation +
 * service-layer guard enforces the "intake required iff project.type=RESEARCH"
 * invariant. See ADR-056.
 */
public record ResearchIntakeCommand(
        String goal,
        String paperContext,
        ContributionType contributionType,
        IntendedOutput intendedOutput,
        AutonomyLevel autonomyLevel,
        List<String> allowedTools,
        String privacyConstraints,
        Long budgetTokens,
        Integer budgetWallClockMinutes,
        Long budgetCostUsdMicros) {}
