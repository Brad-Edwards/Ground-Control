package com.keplerops.groundcontrol.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * Wires the bearer/API access control filter chain (ADR-026 + ADR-037).
 *
 * <p>This chain is scoped to requests carrying {@code Authorization: Bearer …} only, via
 * {@link BearerRequestMatcher}. Browser navigation, SPA XHRs authenticated by the
 * {@code GC_SESSION} cookie, and form-login traffic fall through to {@link BrowserSecurityConfig}
 * (ADR-037 §1, §2). Splitting the chains on the bearer scheme is the simplest stable
 * discriminator and keeps machine traffic stateless and CSRF-exempt as ADR-026 specified.
 *
 * <p>When {@code groundcontrol.security.enabled=true}, the chain runs IP allowlist → bearer
 * token authn → Spring Security authorization, with the path matrix set up so {@code
 * /api/v1/admin/**} and the privileged subpaths require {@code ROLE_ADMIN}, the rest of {@code
 * /api/v1/**} requires authentication, and only health/info actuator probes are anonymous.
 *
 * <p>When disabled, the chain still owns request mapping but every path is permitAll — the same
 * Spring Security framework object stays in charge so behavior never silently degrades. The SPA
 * static-asset and route allowlist that used to live here has moved to {@link
 * BrowserSecurityConfig}, because the API chain now only sees bearer traffic and bearer callers
 * have no reason to fetch SPA HTML.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class ApiSecurityConfig {

    @Bean
    public BearerTokenAuthFilter bearerTokenAuthFilter(SecurityProperties properties) {
        return new BearerTokenAuthFilter(properties);
    }

    @Bean
    public IpAllowlistFilter ipAllowlistFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        return new IpAllowlistFilter(properties, objectMapper);
    }

    /**
     * Spring Boot auto-registers any {@link jakarta.servlet.Filter} bean with the servlet
     * container. Both filters above are also wired into the {@link SecurityFilterChain} below;
     * leaving servlet auto-registration on would run them twice (once outside the security
     * chain). Disabling auto-registration keeps the filters scoped to the chain only.
     */
    @Bean
    public FilterRegistrationBean<BearerTokenAuthFilter> bearerTokenAuthFilterRegistration(
            BearerTokenAuthFilter filter) {
        return disableServletAutoRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<IpAllowlistFilter> ipAllowlistFilterRegistration(IpAllowlistFilter filter) {
        return disableServletAutoRegistration(filter);
    }

    private static <F extends jakarta.servlet.Filter> FilterRegistrationBean<F> disableServletAutoRegistration(
            F filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ApiAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public ApiAccessDeniedHandler apiAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ApiAccessDeniedHandler(objectMapper);
    }

    @Bean
    public IdentityAuthorizationManager identityAuthorizationManager(
            ObjectProvider<IdentityAuthorizationService> authorizationService) {
        return new IdentityAuthorizationManager(authorizationService.getIfAvailable());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            BearerTokenAuthFilter bearerTokenAuthFilter,
            IpAllowlistFilter ipAllowlistFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            IdentityAuthorizationManager identityAuthorizationManager)
            throws Exception {
        // Scope this chain to bearer-only traffic. Spring Security picks the first matching
        // chain (declaration / @Order), so every non-bearer request — browser navigation, SPA
        // XHRs with a session cookie, logins, logouts — falls through to the browser chain
        // configured in BrowserSecurityConfig. ADR-037 §2: the discriminator is the
        // Authorization scheme, not the path or content type.
        http.securityMatcher(new BearerRequestMatcher())
                // CSRF is disabled by design: this is a stateless REST API authenticated by
                // Authorization: Bearer <token>. There are no session cookies, no form-login
                // flow, and no logout endpoint — the three things a CSRF attack relies on to
                // ride a user's authenticated session. Bearer tokens are explicit per request,
                // so a malicious cross-origin page cannot trigger an authenticated call from a
                // signed-in browser. ADR-026 documents this; do not re-enable CSRF without
                // simultaneously moving to cookie-based sessions and re-evaluating the model.
                .csrf(csrf -> csrf.disable())
                // Use Spring Security's CORS support so the registered MVC CorsConfigurationSource
                // is honored on preflight requests. With cors.disable() preflight OPTIONS calls hit
                // the security chain before MVC and would be rejected for protected routes.
                .cors(Customizer.withDefaults())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anon -> anon.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // IpAllowlistFilter must sit ahead of every Spring Security filter so the
                // network gate fires before BearerTokenAuthFilter (which could otherwise
                // accept a token from a non-allowlisted source). Place it before
                // DisableEncodeUrlFilter (the canonical first filter) on this chain too.
                .addFilterBefore(ipAllowlistFilter, DisableEncodeUrlFilter.class)
                .addFilterBefore(bearerTokenAuthFilter, AuthorizationFilter.class)
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler));

        if (!properties.isEnabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(auth -> {
            ApiPathMatrix.applySharedRules(auth, properties, identityAuthorizationManager);
            // No SPA static-asset / route allowlist on this chain. Bearer callers never
            // fetch /index.html or SPA routes; the browser chain owns that surface
            // (BrowserSecurityConfig). Anything outside the shared matrix is fail-closed.
            auth.anyRequest().denyAll();
        });

        return http.build();
    }
}
