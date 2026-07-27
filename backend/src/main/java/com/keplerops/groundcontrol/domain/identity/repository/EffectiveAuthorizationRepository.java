package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.time.Instant;
import java.util.UUID;

public interface EffectiveAuthorizationRepository {

    boolean hasEffectivePermission(UUID userId, PermissionKey permission, UUID projectId, Instant now);

    boolean hasEffectiveProjectAccess(UUID userId, UUID projectId, Instant now);

    boolean hasAnyEffectiveGlobalPermission(PermissionKey permission, Instant now);
}
