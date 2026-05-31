package com.keplerops.groundcontrol.api.compliance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.compliance.model.ComplianceDriftEvent;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftSeverity;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplianceDriftEventResponse(
        UUID id,
        String projectIdentifier,
        ComplianceDriftCategory category,
        ComplianceDriftSeverity severity,
        String sourceEntityType,
        UUID sourceEntityId,
        String affectedEntityType,
        UUID affectedEntityId,
        String summary,
        Instant detectedAt,
        String detectedBy,
        Instant acknowledgedAt,
        String acknowledgedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static ComplianceDriftEventResponse from(ComplianceDriftEvent event) {
        return new ComplianceDriftEventResponse(
                event.getId(),
                event.getProject().getIdentifier(),
                event.getCategory(),
                event.getSeverity(),
                event.getSourceEntityType(),
                event.getSourceEntityId(),
                event.getAffectedEntityType(),
                event.getAffectedEntityId(),
                event.getSummary(),
                event.getDetectedAt(),
                event.getDetectedBy(),
                event.getAcknowledgedAt(),
                event.getAcknowledgedBy(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
