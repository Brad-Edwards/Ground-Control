package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import java.util.Map;
import java.util.UUID;

/**
 * Start a new research run. {@code autonomyLevel} / {@code intendedOutput} are
 * snapshotted from the project's {@link
 * com.keplerops.groundcontrol.domain.research.model.ResearchIntake} when the
 * caller leaves them null, otherwise the supplied values win. {@code
 * gateOverrides} may tighten or relax individual gate behaviors relative to the
 * autonomy-derived default. The run owner is taken from the authenticated server
 * context (ADR-026), not this command, so it is the real starting actor.
 */
public record StartResearchRunCommand(
        UUID projectId,
        String uid,
        AutonomyLevel autonomyLevel,
        IntendedOutput intendedOutput,
        Map<ResearchGatePoint, ResearchGateBehavior> gateOverrides) {}
