package com.keplerops.groundcontrol.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Single source of truth for "API-shaped request" classification on the ADR-037 browser chain.
 *
 * <p>Three browser-chain decisions all hinge on the same predicate:
 *
 * <ol>
 *   <li>The {@link org.springframework.security.web.savedrequest.HttpSessionRequestCache
 *       request cache} must NOT save API requests, or a failed /api/v1/** XHR after session
 *       expiry would become the post-login redirect target.
 *   <li>The {@link DelegatingAuthenticationEntryPointFactory authentication entry point} must
 *       emit a JSON 401 envelope for API requests instead of a 302 to {@code /login}.
 *   <li>{@link PathAwareAccessDeniedHandler} must emit a JSON 403 envelope for API requests
 *       instead of an HTML access-denied page.
 * </ol>
 *
 * <p>Hard-coding the prefix list in three places would let the three sites drift; adding a new
 * docs / API namespace in one but forgetting another silently changes whether the SPA sees
 * JSON or HTML for the same path. This class is the one place where that policy lives.
 */
public final class ApiRequestPaths {

    private static final String[] PREFIXES = {
        "/api/", "/v3/api-docs", "/swagger-ui",
    };

    private static final PathPatternRequestMatcher.Builder PATH_MATCHER_BUILDER =
            PathPatternRequestMatcher.withDefaults();

    private static final RequestMatcher MATCHER = new OrRequestMatcher(
            PATH_MATCHER_BUILDER.matcher("/api/**"),
            PATH_MATCHER_BUILDER.matcher("/v3/api-docs/**"),
            PATH_MATCHER_BUILDER.matcher("/v3/api-docs"),
            PATH_MATCHER_BUILDER.matcher("/swagger-ui/**"),
            PATH_MATCHER_BUILDER.matcher("/swagger-ui.html"));

    private ApiRequestPaths() {
        // utility
    }

    /** Spring Security {@link RequestMatcher} for use in request-cache / entry-point wiring. */
    public static RequestMatcher matcher() {
        return MATCHER;
    }

    /**
     * Servlet-API check for handlers that receive a raw {@link HttpServletRequest}. Uses the
     * URI prefix list so the check works against MockMvc's {@code getRequestURI()} (which is
     * the same value the matcher operates on at runtime in a "/"-mapped servlet).
     */
    public static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
