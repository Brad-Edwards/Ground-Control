package com.keplerops.groundcontrol.api.riskappetite;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response for a {@link RiskAppetiteProfile} (GC-T005). */
public record RiskAppetiteProfileResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String appetiteKey,
        String name,
        String version,
        MethodologyFamily methodologyFamily,
        String appetiteStatement,
        List<ToleranceThreshold> toleranceThresholds,
        RiskAppetiteProfileStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt,
        Instant updatedAt) {

    public static RiskAppetiteProfileResponse from(RiskAppetiteProfile profile) {
        return new RiskAppetiteProfileResponse(
                profile.getId(),
                GraphIds.nodeId(GraphEntityType.RISK_APPETITE_PROFILE, profile.getId()),
                profile.getProject().getIdentifier(),
                profile.getAppetiteKey(),
                profile.getName(),
                profile.getVersion(),
                profile.getMethodologyFamily(),
                profile.getAppetiteStatement(),
                profile.getToleranceThresholds(),
                profile.getStatus(),
                profile.getEffectiveFrom(),
                profile.getEffectiveTo(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
