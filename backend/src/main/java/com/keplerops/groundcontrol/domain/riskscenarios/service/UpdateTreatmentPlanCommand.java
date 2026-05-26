package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UpdateTreatmentPlanCommand(
        String title,
        UUID riskScenarioId,
        TreatmentStrategy strategy,
        String owner,
        String rationale,
        Instant dueDate,
        List<ActionItem> actionItems,
        List<ReassessmentTrigger> reassessmentTriggers,
        UUID methodologyProfileId,
        String methodologyStrategyKey) {}
