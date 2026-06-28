package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * Start a research run. {@code autonomyLevel} / {@code intendedOutput} fall back
 * to the project's research intake snapshot when omitted. {@code gateOverrides}
 * optionally overrides the autonomy-derived behavior per gate point. The run
 * owner is taken from the authenticated server context, not the request body, so
 * it is never a client-supplied identity (ADR-026).
 */
public record ResearchRunRequest(
        @NotBlank @Size(max = 50) String uid,
        AutonomyLevel autonomyLevel,
        IntendedOutput intendedOutput,
        Map<ResearchGatePoint, ResearchGateBehavior> gateOverrides) {

    public StartResearchRunCommand toCommand(UUID projectId) {
        return new StartResearchRunCommand(projectId, uid, autonomyLevel, intendedOutput, gateOverrides);
    }
}
