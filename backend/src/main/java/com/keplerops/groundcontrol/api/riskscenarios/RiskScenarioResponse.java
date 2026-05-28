package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.time.Instant;
import java.util.UUID;

public record RiskScenarioResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String uid,
        String title,
        RiskScenarioStatus status,
        String threat,
        String method,
        String asset,
        String effect,
        String timeHorizon,
        String fairSentence,
        Instant createdAt,
        Instant updatedAt,
        String createdBy) {

    public static RiskScenarioResponse from(RiskScenario rs) {
        return new RiskScenarioResponse(
                rs.getId(),
                GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, rs.getId()),
                rs.getProject().getIdentifier(),
                rs.getUid(),
                rs.getTitle(),
                rs.getStatus(),
                rs.getThreat(),
                rs.getMethod(),
                rs.getAsset(),
                rs.getEffect(),
                rs.getTimeHorizon(),
                rs.getFairSentence(),
                rs.getCreatedAt(),
                rs.getUpdatedAt(),
                rs.getCreatedBy());
    }
}
