package com.keplerops.groundcontrol.domain.riskcontrol.service;

import java.util.UUID;

/** Command to update a {@link com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation}. */
public record UpdateScopedControlImplementationCommand(
        UUID projectId, UUID sciId, String name, String implementationScope, UUID operationalAssetId) {}
