package com.keplerops.groundcontrol.shared.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Single source of truth for the API authorization matrix shared by {@link ApiSecurityConfig}
 * (bearer chain) and {@link BrowserSecurityConfig} (browser chain).
 *
 * <p>ADR-026 + ADR-037: both chains must enforce identical authorities on {@code /api/v1/**} so
 * a bearer caller and a session caller see the same path policy. Without this helper the two
 * configurations carry copy-pasted rules; the next privileged endpoint added in one place but
 * forgotten in the other would silently authorize bearer and session traffic differently. The
 * helper is intentionally tiny — just the shared rules — so the per-chain blocks stay readable
 * and the chain-specific concerns (CSRF, form login, SPA static assets) remain inline at the
 * call site.
 */
final class ApiPathMatrix {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String RISK_APPETITE_PROFILES = "/api/v1/risk-appetite-profiles";
    private static final String RISK_APPETITE_PROFILES_WILDCARD = "/api/v1/risk-appetite-profiles/**";

    private ApiPathMatrix() {
        // utility
    }

    /**
     * Apply the shared actuator + OpenAPI + {@code /api/v1/**} rules to {@code auth}. The
     * caller is responsible for the chain-specific rules that come <em>before</em> (the
     * bearer chain has nothing extra; the browser chain has {@code /login}, {@code /logout},
     * static asset paths) and the {@code anyRequest().denyAll()} terminator.
     */
    static void applySharedRules(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
            SecurityProperties properties) {
        auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                .permitAll()
                .requestMatchers("/error")
                .permitAll();
        if (properties.isOpenapiPublic()) {
            auth.requestMatchers(
                            "/api/openapi.json",
                            "/api/docs/**",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html")
                    .permitAll();
        } else {
            auth.requestMatchers(
                            "/api/openapi.json",
                            "/api/docs/**",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html")
                    .authenticated();
        }
        auth.requestMatchers("/api/v1/admin/**")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/embeddings/**")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/analysis/sweep/**")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/pack-registry/**")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/trust-policies/**")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/pack-install-records/**")
                .hasRole(ROLE_ADMIN)
                // MCP tool-usage reads are cross-project operational telemetry, so every GET under
                // this prefix is admin-only: an ordinary authenticated caller (e.g. via gc_query)
                // must not read other projects' tool usage. The capture write (POST /events) is NOT
                // gated here — every authenticated MCP session must record its own events — so it
                // falls through to the authenticated() rule below.
                .requestMatchers(HttpMethod.GET, "/api/v1/mcp-tool-usage", "/api/v1/mcp-tool-usage/**")
                .hasRole(ROLE_ADMIN)
                // Issue #859: the cross-project workflow-run rollup is operator telemetry spanning every
                // project, so it is admin-only — an explicit authorization decision, not an accidental
                // fall-through from a project-scoped read. The project-scoped workflow-run reads/writes
                // (POST /workflow-runs, GET /workflow-runs, GET /workflow-runs/aggregate, etc.) resolve
                // through ProjectService and fall through to the authenticated() rule below.
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/workflow-runs/cross-project-aggregate",
                        "/api/v1/workflow-runs/cross-project-aggregate/**")
                .hasRole(ROLE_ADMIN)
                // GC-T005: risk appetite/tolerance governs org-wide escalation policy, so writes are
                // admin-only (tampering would suppress escalations across every risk). Reads fall
                // through to authenticated() so any project member can query the posture.
                .requestMatchers(HttpMethod.POST, RISK_APPETITE_PROFILES, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/**")
                .authenticated()
                .requestMatchers("/actuator/**")
                .denyAll();
    }
}
