package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateTreatmentPlanRequest(
        @Size(max = 200) String title,
        UUID riskScenarioId,
        TreatmentStrategy strategy,
        @Size(max = 200) String owner,
        String rationale,
        Instant dueDate,
        List<Map<String, Object>> actionItems,
        List<String> reassessmentTriggers,
        UUID methodologyProfileId,
        @Size(max = 100) String methodologyStrategyKey) {}
