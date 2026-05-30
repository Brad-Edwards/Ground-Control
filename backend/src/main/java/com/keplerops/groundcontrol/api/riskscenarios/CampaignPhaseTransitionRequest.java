package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import jakarta.validation.constraints.NotNull;

public record CampaignPhaseTransitionRequest(@NotNull CampaignPhase phase) {}
