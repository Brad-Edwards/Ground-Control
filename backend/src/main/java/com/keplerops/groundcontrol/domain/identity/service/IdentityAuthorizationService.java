package com.keplerops.groundcontrol.domain.identity.service;

import com.keplerops.groundcontrol.domain.identity.repository.EffectiveAuthorizationRepository;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IdentityAuthorizationService {

    private final EffectiveAuthorizationRepository repository;
    private final Clock clock;

    public IdentityAuthorizationService(EffectiveAuthorizationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public boolean isAllowed(UUID userId, PermissionKey permission, UUID projectId) {
        if (userId == null || permission == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!repository.hasEffectivePermission(userId, permission, projectId, now)) {
            return false;
        }
        return projectId == null || repository.hasEffectiveProjectAccess(userId, projectId, now);
    }
}
