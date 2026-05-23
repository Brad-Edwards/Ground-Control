package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateTreatmentPlanCommand(
        String title,
        UUID riskScenarioId,
        TreatmentStrategy strategy,
        String owner,
        String rationale,
        Instant dueDate,
        List<Map<String, Object>> actionItems,
        List<String> reassessmentTriggers,
        UUID methodologyProfileId,
        String methodologyStrategyKey) {}
