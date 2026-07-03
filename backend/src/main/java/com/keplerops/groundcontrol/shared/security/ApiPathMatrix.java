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
    private static final String EVIDENCE_CAMPAIGNS = "/api/v1/evidence-campaigns";
    private static final String EVIDENCE_CAMPAIGNS_WILDCARD = "/api/v1/evidence-campaigns/**";
    private static final String DATA_CLASSIFICATION_LATTICE = "/api/v1/data-classification/lattice";
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
                // GC-T005: risk appetite/tolerance governs org-wide escalation policy, so writes are
                // admin-only (tampering would suppress escalations across every risk). Reads fall
                // through to authenticated() so any project member can query the posture.
                .requestMatchers(HttpMethod.POST, RISK_APPETITE_PROFILES, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, RISK_APPETITE_PROFILES_WILDCARD)
                .hasRole(ROLE_ADMIN)
                // GC-S005: a campaign is a stored directive to reach out to an external system with the
                // campaign's credential reference and ingest the result as evidence. Every write that
                // configures or enables that outbound collection is therefore admin-only: create (an
                // ACTIVE campaign defaults firstRunAt to now), update (can change connectionEndpoint or
                // credentialRef), pause/resume (gates whether the sweep executes), and the on-demand
                // trigger (forces an immediate collection). Admin-gating only the trigger left the other
                // writes at the generic authenticated() rule, so a non-admin could create or re-point an
                // ACTIVE campaign and let the scheduled sweep perform the credentialed call. Gate POST
                // (create + the /{id}/{action} routes) and PUT across the whole surface; the GET reads
                // (list, get, runs) fall through to authenticated() so any project member can query.
                .requestMatchers(HttpMethod.POST, EVIDENCE_CAMPAIGNS, EVIDENCE_CAMPAIGNS_WILDCARD)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT, EVIDENCE_CAMPAIGNS_WILDCARD)
                .hasRole(ROLE_ADMIN)
                // GC-GRC-006: the data classification lattice is the information-flow policy that the
                // deterministic leak detector evaluates against. Tampering with the taxonomy or
                // permitted-flow relation would silently suppress real PII/secret-leak findings
                // (GC-TM-010), so writes are admin-only. The lattice read and the read-only evaluation
                // resolve through ProjectService and fall through to the authenticated() rule below.
                .requestMatchers(HttpMethod.PUT, DATA_CLASSIFICATION_LATTICE)
                .hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, DATA_CLASSIFICATION_LATTICE)
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
                .requestMatchers("/api/v1/**")
                .authenticated()
                .requestMatchers("/actuator/**")
                .denyAll();
    }
}
