package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskAppetiteProfileResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String profileKey,
        String name,
        String version,
        String appetiteStatement,
        String owner,
        boolean active,
        List<RiskAppetiteTolerance> tolerances,
        Instant createdAt,
        Instant updatedAt) {

    public static RiskAppetiteProfileResponse from(RiskAppetiteProfile profile) {
        return new RiskAppetiteProfileResponse(
                profile.getId(),
                GraphIds.nodeId(GraphEntityType.RISK_APPETITE_PROFILE, profile.getId()),
                profile.getProject().getIdentifier(),
                profile.getProfileKey(),
                profile.getName(),
                profile.getVersion(),
                profile.getAppetiteStatement(),
                profile.getOwner(),
                profile.isActive(),
                profile.getTolerances(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
