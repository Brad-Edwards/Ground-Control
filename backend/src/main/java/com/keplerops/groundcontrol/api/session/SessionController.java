package com.keplerops.groundcontrol.api.session;

import com.keplerops.groundcontrol.domain.exception.AuthenticationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the authenticated browser principal to the console shell so the user menu can render
 * who is signed in (GC-Q015 clause (a)).
 *
 * <p>The endpoint falls under the {@code /api/v1/**} {@code authenticated()} rule in
 * {@link com.keplerops.groundcontrol.shared.security.ApiPathMatrix}, so an unauthenticated request
 * is rejected by the security chain before reaching this controller in production. The explicit
 * guard below covers the {@code groundcontrol.security.enabled=false} permit-all profile, where an
 * anonymous request can arrive here and must still be told it has no session rather than being
 * handed an empty identity.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    @GetMapping
    public SessionResponse currentSession(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationException("No authenticated session");
        }
        var roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .sorted()
                .toList();
        return new SessionResponse(authentication.getName(), roles, roles.contains(ROLE_ADMIN));
    }
}
