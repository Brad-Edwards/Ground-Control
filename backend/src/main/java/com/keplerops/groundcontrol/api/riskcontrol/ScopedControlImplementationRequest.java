package com.keplerops.groundcontrol.api.riskcontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ScopedControlImplementationRequest(
        @NotBlank String uid,
        @NotNull UUID controlId,
        @NotBlank String name,
        String implementationScope,
        UUID operationalAssetId) {}
