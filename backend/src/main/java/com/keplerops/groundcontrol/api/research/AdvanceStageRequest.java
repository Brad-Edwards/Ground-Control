package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import jakarta.validation.constraints.NotNull;

/** Advance the run into {@code targetStage} (must be the immediate next stage). */
public record AdvanceStageRequest(@NotNull ResearchRunStage targetStage) {

    public AdvanceStageCommand toCommand() {
        return new AdvanceStageCommand(targetStage);
    }
}
