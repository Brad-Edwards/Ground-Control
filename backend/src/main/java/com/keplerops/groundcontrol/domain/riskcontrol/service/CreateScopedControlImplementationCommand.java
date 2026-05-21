package com.keplerops.groundcontrol.domain.riskcontrol.service;

import java.util.UUID;

/** Command to create a {@link com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation}. */
public record CreateScopedControlImplementationCommand(
        UUID projectId, String uid, UUID controlId, String name, String implementationScope, UUID operationalAssetId) {}
