package com.keplerops.groundcontrol.domain.derivation.service;

import java.util.UUID;

public record DerivationAdapterRequest(UUID projectId, String projectIdentifier, DerivationScope scope) {}
