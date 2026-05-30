package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record KeyRiskIndicatorResponse(
        UUID id,
        String graphNodeId,
        String projectIdentifier,
        String uid,
        String name,
        String description,
        String metricUnit,
        BigDecimal yellowThreshold,
        BigDecimal redThreshold,
        String direction,
        String owner,
        UUID riskRegisterRecordId,
        UUID riskScenarioId,
        BigDecimal currentValue,
        KriThresholdBand currentBand,
        Instant lastMeasuredAt,
        Instant createdAt,
        Instant updatedAt) {

    public static KeyRiskIndicatorResponse from(KeyRiskIndicator kri) {
        return new KeyRiskIndicatorResponse(
                kri.getId(),
                GraphIds.nodeId(GraphEntityType.KEY_RISK_INDICATOR, kri.getId()),
                kri.getProject().getIdentifier(),
                kri.getUid(),
                kri.getName(),
                kri.getDescription(),
                kri.getMetricUnit(),
                kri.getYellowThreshold(),
                kri.getRedThreshold(),
                kri.getDirection(),
                kri.getOwner(),
                kri.getRiskRegisterRecord() != null
                        ? kri.getRiskRegisterRecord().getId()
                        : null,
                kri.getRiskScenario() != null ? kri.getRiskScenario().getId() : null,
                kri.getCurrentValue(),
                kri.getCurrentBand(),
                kri.getLastMeasuredAt(),
                kri.getCreatedAt(),
                kri.getUpdatedAt());
    }
}
