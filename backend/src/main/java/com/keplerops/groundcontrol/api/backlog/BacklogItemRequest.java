package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import jakarta.validation.constraints.NotBlank;

public record BacklogItemRequest(
        @NotBlank String uid,
        @NotBlank String title,
        String description,
        CostOfDelayComponent userBusinessValue,
        CostOfDelayComponent timeCriticality,
        CostOfDelayComponent riskReductionOpportunityEnablement,
        CostOfDelayComponent jobDuration) {}
