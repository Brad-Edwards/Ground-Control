package com.keplerops.groundcontrol.api.riskcontrol;

import java.util.UUID;

public record UpdateScopedControlImplementationRequest(
        String name, String implementationScope, UUID operationalAssetId) {}
