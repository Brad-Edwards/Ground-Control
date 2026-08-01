package com.keplerops.groundcontrol.domain.identity.service;

import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.util.UUID;

/**
 * Domain-facing delegation boundary. The shared-security adapter owns Spring authentication and
 * the narrow legacy-admin bridge; identity services depend only on this contract.
 */
public interface IdentityAdministrationPolicy {

    void requireIdentityAdministration();

    void requirePermissionDelegation(PermissionKey permission, UUID projectId);

    void requireProjectAccessDelegation(UUID projectId);
}
