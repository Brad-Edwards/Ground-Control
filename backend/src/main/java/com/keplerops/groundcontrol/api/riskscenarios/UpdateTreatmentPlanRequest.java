package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UpdateTreatmentPlanRequest(
        @Size(max = 200) String title,
        UUID riskScenarioId,
        TreatmentStrategy strategy,
        @Size(max = 200) String owner,
        String rationale,
        Instant dueDate,
        @Valid List<@NotNull ActionItem> actionItems,
        @Valid List<@NotNull ReassessmentTrigger> reassessmentTriggers,
        UUID methodologyProfileId,
        @Size(max = 100) String methodologyStrategyKey) {}
