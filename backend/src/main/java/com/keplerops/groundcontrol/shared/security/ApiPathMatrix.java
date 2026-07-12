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
    private static final String RESEARCH_OPERATION_AUTHORIZATION_DECISION =
            "/api/v1/research-runs/*/operation-authorizations/*/decision";
    private static final String RESEARCH_OPERATION_AUTHORIZATION_CONSUME =
            "/api/v1/research-runs/*/operation-authorizations/*/consume";

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
                // GC-RSCH-R005 / ADR-086 §3: approving a research high-risk operation (generated code
                // execution, browser activity, lab/hardware action, external write) is an
                // admin/operator decision — an AUTONOMOUS run may propose but must never approve its
                // own operations. Gate only the decision route to ROLE_ADMIN; propose (POST),
                // list/get (GET), and consume fall through to the authenticated() rule so any project
                // member may propose or read an authorization. The consume route spends a one-time-use
                // APPROVED authorization, so it is the trusted executor/operator boundary — gate it to
                // ROLE_ADMIN too, otherwise any authenticated project member who can read an
                // authorization id (via list/get) could burn an approved high-risk-operation grant.
                .requestMatchers(
                        HttpMethod.POST,
                        RESEARCH_OPERATION_AUTHORIZATION_DECISION,
                        RESEARCH_OPERATION_AUTHORIZATION_CONSUME)
                .hasRole(ROLE_ADMIN)
                // GC-O009 #1278: sending an operator signal (cancel, retry-from, review-cap
                // disposition) to a workflow execution is a privileged control action, so it is
                // admin-only until GC-P024 project-scoped gate authority lands (ADR-085 §54). The
                // project-scoped start (POST /workflow-executions) and status reads (GET
                // /workflow-executions, GET /workflow-executions/{id}) resolve through ProjectService
                // and fall through to the authenticated() rule below.
                .requestMatchers(HttpMethod.POST, "/api/v1/workflow-executions/*/signals")
                .hasRole(ROLE_ADMIN)
                .requestMatchers("/api/v1/**")
                .authenticated()
                .requestMatchers("/actuator/**")
                .denyAll();
    }
}
