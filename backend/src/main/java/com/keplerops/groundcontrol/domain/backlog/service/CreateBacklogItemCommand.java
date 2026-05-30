package com.keplerops.groundcontrol.domain.backlog.service;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import java.util.UUID;

public record CreateBacklogItemCommand(
        UUID projectId,
        String uid,
        String title,
        String description,
        CostOfDelayComponent userBusinessValue,
        CostOfDelayComponent timeCriticality,
        CostOfDelayComponent riskReductionOpportunityEnablement,
        CostOfDelayComponent jobDuration) {}
