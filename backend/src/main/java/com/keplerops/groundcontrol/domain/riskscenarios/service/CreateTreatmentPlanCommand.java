package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateTreatmentPlanCommand(
        UUID projectId,
        String uid,
        String title,
        UUID riskRegisterRecordId,
        UUID riskScenarioId,
        TreatmentStrategy strategy,
        String owner,
        String rationale,
        Instant dueDate,
        TreatmentPlanStatus status,
        List<ActionItem> actionItems,
        List<ReassessmentTrigger> reassessmentTriggers,
        UUID methodologyProfileId,
        String methodologyStrategyKey) {}
