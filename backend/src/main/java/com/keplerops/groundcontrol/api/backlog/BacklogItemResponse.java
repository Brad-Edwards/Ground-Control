package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import java.time.Instant;
import java.util.UUID;

public record BacklogItemResponse(
        UUID id,
        String projectIdentifier,
        String uid,
        String title,
        String description,
        BacklogItemStatus status,
        CostOfDelayComponent userBusinessValue,
        CostOfDelayComponent timeCriticality,
        CostOfDelayComponent riskReductionOpportunityEnablement,
        CostOfDelayComponent jobDuration,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static BacklogItemResponse from(BacklogItem item) {
        return new BacklogItemResponse(
                item.getId(),
                item.getProject().getIdentifier(),
                item.getUid(),
                item.getTitle(),
                item.getDescription(),
                item.getStatus(),
                item.getUserBusinessValue(),
                item.getTimeCriticality(),
                item.getRiskReductionOpportunityEnablement(),
                item.getJobDuration(),
                item.getCreatedBy(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
