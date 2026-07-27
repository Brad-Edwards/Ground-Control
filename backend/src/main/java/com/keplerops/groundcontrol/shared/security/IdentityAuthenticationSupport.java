package com.keplerops.groundcontrol.shared.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;

final class IdentityAuthenticationSupport {

    private static final String LEGACY_ADMIN_AUTHORITY = "ROLE_ADMIN";

    private IdentityAuthenticationSupport() {
        // utility
    }

    static boolean isLegacyAdmin(Authentication authentication) {
        return isAuthenticated(authentication)
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> LEGACY_ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    static Optional<UUID> identityUserId(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof IdentityPrincipal principal) {
            return Optional.of(principal.userId());
        }
        if (authentication.getPrincipal() instanceof UUID userId) {
            return Optional.of(userId);
        }
        if (authentication.getDetails() instanceof IdentityPrincipal principal) {
            return Optional.of(principal.userId());
        }
        return Optional.empty();
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
