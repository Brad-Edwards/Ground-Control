package com.keplerops.groundcontrol.domain.backlog.service;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;

public record UpdateBacklogItemCommand(
        String title,
        String description,
        CostOfDelayComponent userBusinessValue,
        CostOfDelayComponent timeCriticality,
        CostOfDelayComponent riskReductionOpportunityEnablement,
        CostOfDelayComponent jobDuration) {}
