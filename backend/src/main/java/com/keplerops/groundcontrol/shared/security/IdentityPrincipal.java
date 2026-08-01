package com.keplerops.groundcontrol.shared.security;

import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Stable, credential-free authenticated identity carried by future authentication adapters.
 *
 * <p>Issue #1282 deliberately does not replace the existing credential stores. This principal
 * gives those later adapters a UUID-based hand-off into domain authorization without coupling
 * identity entities to Spring Security.
 */
public record IdentityPrincipal(UUID userId, String loginName) implements AuthenticatedPrincipal {

    public IdentityPrincipal {
        Objects.requireNonNull(userId, "userId");
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("loginName must not be blank");
        }
    }

    @Override
    public String getName() {
        return loginName;
    }
}
