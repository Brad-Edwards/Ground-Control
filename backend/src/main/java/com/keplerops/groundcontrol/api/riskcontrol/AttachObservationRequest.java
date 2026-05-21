package com.keplerops.groundcontrol.api.riskcontrol;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AttachObservationRequest(@NotNull UUID observationId) {}
