package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * REST input shape for ResearchIntake. Used both nested inside ProjectRequest
 * on create and as the body of {@code PUT /research-intake/{identifier}} on
 * full-replacement updates. Required fields are enforced by Bean Validation
 * here and re-checked at the service layer for bypass writes. {@code egressPolicy}
 * is the optional structured, default-deny data-egress allowlist (GC-RSCH-N006 /
 * ADR-086 §2); absent/empty means local-only. See ADR-056.
 */
public record ResearchIntakeRequest(
        @NotBlank @Size(max = 4000) String goal,
        @Size(max = 8000) String paperContext,
        @NotNull ContributionType contributionType,
        @NotNull IntendedOutput intendedOutput,
        @NotNull AutonomyLevel autonomyLevel,
        @NotNull List<@NotBlank @Size(max = 100) String> allowedTools,
        @Size(max = 4000) String privacyConstraints,
        @Size(max = 200) List<@NotNull @Valid ResearchEgressAllowance> egressPolicy,
        @PositiveOrZero Long budgetTokens,
        @PositiveOrZero Integer budgetWallClockMinutes,
        @PositiveOrZero Long budgetCostUsdMicros) {

    public ResearchIntakeCommand toCommand() {
        return new ResearchIntakeCommand(
                goal,
                paperContext,
                contributionType,
                intendedOutput,
                autonomyLevel,
                allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools),
                privacyConstraints,
                egressPolicy == null ? new ArrayList<>() : new ArrayList<>(egressPolicy),
                budgetTokens,
                budgetWallClockMinutes,
                budgetCostUsdMicros);
    }
}
