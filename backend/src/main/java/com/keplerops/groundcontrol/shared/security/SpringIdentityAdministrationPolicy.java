package com.keplerops.groundcontrol.shared.security;

import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAdministrationPolicy;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Spring Security adapter for domain-level identity administration and delegation checks. */
@Component
public class SpringIdentityAdministrationPolicy implements IdentityAdministrationPolicy {

    private final IdentityAuthorizationService authorizationService;
    private final boolean securityEnabled;

    public SpringIdentityAdministrationPolicy(
            IdentityAuthorizationService authorizationService, SecurityProperties securityProperties) {
        this.authorizationService = authorizationService;
        this.securityEnabled = securityProperties.isEnabled();
    }

    @Override
    public void requireIdentityAdministration() {
        require(PermissionKey.IDENTITY_ADMIN, null);
    }

    @Override
    public void requirePermissionDelegation(PermissionKey permission, UUID projectId) {
        require(permission, projectId);
    }

    @Override
    public void requireProjectAccessDelegation(UUID projectId) {
        require(PermissionKey.PROJECT_ACCESS_ADMIN, projectId);
    }

    private void require(PermissionKey permission, UUID projectId) {
        if (!securityEnabled) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (IdentityAuthenticationSupport.isLegacyAdmin(authentication)) {
            return;
        }
        boolean allowed = IdentityAuthenticationSupport.identityUserId(authentication)
                .map(userId -> authorizationService.isAllowed(userId, permission, projectId))
                .orElse(false);
        if (!allowed) {
            throw new AuthorizationException("Identity permission required: " + permission);
        }
    }
}
