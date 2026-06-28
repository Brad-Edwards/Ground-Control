package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;

/** Advance a run into {@code targetStage} (must be the immediate next stage). */
public record AdvanceStageCommand(ResearchRunStage targetStage) {}
