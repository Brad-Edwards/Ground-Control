package com.keplerops.groundcontrol.shared.security;

import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Spring adapter for the identity administration route.
 *
 * <p>The legacy {@code ROLE_ADMIN} compatibility bridge is intentionally confined to the path
 * where this manager is installed. UUID-backed principals authorize through the closed
 * permission catalog.
 */
public final class IdentityAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final IdentityAuthorizationService authorizationService;

    public IdentityAuthorizationManager(IdentityAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authenticationSupplier, RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();
        if (IdentityAuthenticationSupport.isLegacyAdmin(authentication)) {
            return new AuthorizationDecision(true);
        }
        boolean granted = IdentityAuthenticationSupport.identityUserId(authentication)
                .map(userId -> authorizationService != null
                        && authorizationService.isAllowed(userId, PermissionKey.IDENTITY_ADMIN, null))
                .orElse(false);
        return new AuthorizationDecision(granted);
    }
}
