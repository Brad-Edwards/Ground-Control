package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;

public record UpdateBacklogItemRequest(
        String title,
        String description,
        CostOfDelayComponent userBusinessValue,
        CostOfDelayComponent timeCriticality,
        CostOfDelayComponent riskReductionOpportunityEnablement,
        CostOfDelayComponent jobDuration) {}
