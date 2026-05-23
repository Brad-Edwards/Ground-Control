package com.keplerops.groundcontrol.shared.security;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;

/**
 * Wires the ADR-037 browser session filter chain.
 *
 * <p>Catches every request {@link ApiSecurityConfig}'s bearer-scoped chain does not (i.e., any
 * request without an {@code Authorization: Bearer …} header). The chain runs the same
 * {@link IpAllowlistFilter} as the API chain (network gate applies to all traffic) and then
 * Spring Security's standard form-login machinery against a JDBC-backed user store with BCrypt
 * password hashes.
 *
 * <p>The SPA owns {@code GET /login}: {@code .loginPage("/login")} disables Spring's
 * {@code DefaultLoginPageGeneratingFilter} so the React login page is served by
 * {@link com.keplerops.groundcontrol.infrastructure.web.SpaController} (which forwards to
 * {@code index.html}). Spring still processes {@code POST /login}
 * form-urlencoded via {@code UsernamePasswordAuthenticationFilter} at
 * {@code loginProcessingUrl("/login")}. The {@code XSRF-TOKEN} cookie written by
 * {@link CookieCsrfTokenRepository} on the GET is echoed as {@code X-XSRF-TOKEN} by the React
 * login form, satisfying the CSRF gate on the POST.
 *
 * <p>State-changing browser requests (POST/PUT/PATCH/DELETE) carry the {@code GC_SESSION}
 * cookie; CSRF protection is enabled through a {@link CookieCsrfTokenRepository} configured
 * with {@code HttpOnly=false} so the SPA can read the {@code XSRF-TOKEN} cookie and echo
 * {@code X-XSRF-TOKEN} on its XHR calls (the double-submit cookie pattern). The
 * {@link DelegatingAuthenticationEntryPointFactory delegating entry point} keeps SPA XHRs
 * returning JSON 401 envelopes while regular browser navigation gets a 302 to {@code /login};
 * the {@link PathAwareAccessDeniedHandler access-denied handler} mirrors that policy for 403s.
 *
 * <p>When {@code groundcontrol.security.enabled=false} (dev/test profile), this chain mirrors
 * the API chain's permit-all fallback exactly: CSRF, form login, logout, and sessions are all
 * <em>disabled</em>, every path is {@code permitAll}, and the IP allowlist still runs. The
 * security-enabled branch is the one that turns on the form-login / CSRF / cookie machinery,
 * so existing {@code @SpringBootTest} suites that POST without a CSRF token (a pre-existing
 * convention for {@code security.enabled=false} profiles) keep working unchanged.
 */
@Configuration
@EnableWebSecurity
public class BrowserSecurityConfig {

    static final String LOGIN_PATH = "/login";
    static final String LOGOUT_PATH = "/logout";

    private static final java.util.Set<String> SAFE_HTTP_METHODS = java.util.Set.of("GET", "HEAD", "TRACE", "OPTIONS");

    /**
     * Predicate for {@link org.springframework.security.web.csrf.CsrfFilter}: require CSRF on
     * every state-changing browser-chain request, with one narrow carve-out — unauthenticated
     * {@code /api/v1/**} mutations. The previous version of this matcher exempted ALL
     * state-changing requests without a server-side session, which silently accidentally
     * exempted {@code POST /login} itself: {@code GET /login} writes the XSRF cookie via
     * {@link CookieCsrfTokenRepository} without creating a session, so a real browser's first
     * login POST arrives without one. Codex cycle 4 caught the resulting login-CSRF /
     * session-fixation-by-credential-swap attack — an attacker hosts an auto-submitting form
     * to {@code /login} with credentials they control, the victim's browser POSTs without a
     * CSRF check, and the victim's subsequent SPA activity is attributed to the attacker's
     * principal. The SPA login form reads the {@code XSRF-TOKEN} cookie (written by
     * {@link CookieCsrfTokenRepository} on the GET) and echoes it as {@code X-XSRF-TOKEN},
     * satisfying this gate.
     *
     * <p>Rules now:
     *
     * <ul>
     *   <li>Safe methods (GET/HEAD/TRACE/OPTIONS): no CSRF (default behavior).
     *   <li>Unauthenticated API-shaped mutations: no CSRF, so the request reaches the auth
     *       entry point and gets a JSON 401 envelope instead of a 403 CSRF response. The
     *       request has no session and therefore no CSRF-rideable surface.
     *   <li>Everything else, including {@code /login}, every authenticated SPA mutation, and
     *       {@code /logout}: CSRF required.
     * </ul>
     */
    private static boolean requiresCsrf(jakarta.servlet.http.HttpServletRequest request) {
        // CSRF only matters on state-changing methods and only when there's an authenticated
        // session to ride. The expression below collapses both gates so SonarCloud S1126's
        // "single return" rule is satisfied without obscuring the contract.
        return !SAFE_HTTP_METHODS.contains(request.getMethod())
                && (!ApiRequestPaths.isApiRequest(request) || request.getSession(false) != null);
    }

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        // BCrypt strength 12 — Spring Security's default is 10. Twelve roughly doubles the work
        // factor; on a modest dev box a hash takes ~200 ms which is acceptable for an admin
        // login but expensive enough to slow offline cracking. Increase, never decrease, in
        // future revisions.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdbcUserDetailsManager userDetailsManager(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdbcTemplate userAdminJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * SessionRegistry tracks every authenticated browser session by principal so admin
     * mutations (role change, disable, delete) can expire the affected user's live sessions
     * immediately. Without it a demoted/disabled/deleted user keeps their authenticated cookie
     * working until natural session timeout — ADR-037 §1 says authorities must follow the
     * current SecurityContext, so we close that gap here.
     */
    @Bean
    @ConditionalOnMissingBean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Bridges Tomcat's {@link jakarta.servlet.http.HttpSessionEvent} stream into Spring's
     * application context so {@link SessionRegistryImpl} sees session-destroyed events.
     * Without it the registry leaks principal entries after natural session expiry.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain browserSecurityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            IpAllowlistFilter ipAllowlistFilter,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ApiAccessDeniedHandler apiAccessDeniedHandler,
            SessionRegistry sessionRegistry)
            throws Exception {

        if (!properties.isEnabled()) {
            // Mirror the API chain's security-disabled fallback exactly: no CSRF, no form
            // login, no sessions — just the IP allowlist + permitAll. This is what dev/test
            // profiles have always done; turning on CSRF here would break every
            // @SpringBootTest that POSTs without a token.
            http.csrf(csrf -> csrf.disable())
                    .cors(Customizer.withDefaults())
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable())
                    .logout(logout -> logout.disable())
                    .anonymous(anon -> anon.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(ipAllowlistFilter, DisableEncodeUrlFilter.class)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        // Security-enabled path: form login + CSRF + sessions + path matrix.
        //
        // IpAllowlistFilter is placed BEFORE DisableEncodeUrlFilter (the first filter Spring
        // Security installs) so the network gate runs ahead of UsernamePasswordAuthenticationFilter
        // and DefaultLoginPageGeneratingFilter. Placing it `before AuthorizationFilter` (which
        // sits later in the chain) would let a non-allowlisted attacker hit /login and either
        // probe the credential oracle or, with a valid password, receive a session cookie —
        // both before the IP gate fires.
        http.addFilterBefore(ipAllowlistFilter, DisableEncodeUrlFilter.class).cors(Customizer.withDefaults());

        // CSRF: cookie-based repository so the SPA's fetch wrapper can read XSRF-TOKEN and
        // echo X-XSRF-TOKEN on mutations. The default form-login page (rendered by Spring's
        // LoginPageGeneratingFilter) includes the same token as a hidden form field, so the
        // first login also passes the CSRF gate without the SPA being involved.
        //
        // requireCsrfProtectionMatcher: enforce CSRF only on session-bearing requests. CSRF is
        // a session-rider attack — without a session there is nothing to ride. Without this
        // override an unauthenticated POST/PUT/PATCH/DELETE to /api/v1/** falls into the
        // browser chain (no `Authorization: Bearer …`), CsrfFilter runs before authentication,
        // and the caller gets a 403 CSRF response instead of the established 401
        // `authentication_required` envelope. AuditActorProvenanceIntegrationTest depends on
        // the 401 contract. Session-authenticated mutations still go through the CSRF gate
        // because they carry a session id; the form-login POST itself has a session attached
        // from the preceding GET /login.
        var csrfTokenHandler = new CsrfTokenRequestAttributeHandler();
        csrfTokenHandler.setCsrfRequestAttributeName(null);
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfTokenHandler)
                .requireCsrfProtectionMatcher(BrowserSecurityConfig::requiresCsrf));

        AccessDeniedHandler fallbackAccessDenied = new AccessDeniedHandlerImpl();
        AccessDeniedHandler accessDeniedHandler =
                new PathAwareAccessDeniedHandler(apiAccessDeniedHandler, fallbackAccessDenied);

        http.exceptionHandling(ex -> ex.authenticationEntryPoint(
                        DelegatingAuthenticationEntryPointFactory.build(apiAuthenticationEntryPoint, LOGIN_PATH))
                .accessDeniedHandler(accessDeniedHandler));

        // RequestCache that never saves API-shaped URLs. Without this filter an unauthenticated
        // /api/v1/** XHR (the SPA's typical first call when its session expired) would land
        // in HttpSessionRequestCache; after the user logs in, Spring's default success
        // handler would redirect the browser to the JSON URL instead of the SPA route the
        // user was actually on (codex / ADR-037). The classification predicate is shared with
        // the entry point + access-denied handler via ApiRequestPaths, so the three sites
        // cannot drift against one another.
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new NegatedRequestMatcher(ApiRequestPaths.matcher()));
        http.requestCache(cache -> cache.requestCache(requestCache));

        // .loginPage(LOGIN_PATH) disables DefaultLoginPageGeneratingFilter so the SPA owns
        // GET /login. Spring still runs UsernamePasswordAuthenticationFilter at
        // loginProcessingUrl("/login"), so POST /login form-urlencoded continues to work.
        // .failureUrl and .defaultSuccessUrl make the redirect targets explicit so the
        // behaviour is not silently altered by future Spring Security upgrades.
        http.formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .failureUrl(LOGIN_PATH + "?error")
                        .defaultSuccessUrl("/", false)
                        .permitAll())
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler((request, response, authentication) ->
                                // Return 204 on logout. SPA-initiated XHR logouts can clear
                                // local state without parsing an HTML body; regular browser
                                // navigations to /logout receive the same 204 and the SPA's
                                // 401 handler routes to /login on the next XHR.
                                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT))
                        .deleteCookies("XSRF-TOKEN")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // maximumSessions(-1).sessionRegistry(...) is what wires the
                        // Spring-Security concurrent-session machinery onto this chain. It does
                        // three jobs at once: (a) installs RegisterSessionAuthenticationStrategy
                        // as part of the default composite (so admin mutations can target
                        // sessions by principal), (b) installs ConcurrentSessionFilter so
                        // SessionInformation.expireNow() is actually enforced on the next
                        // request, and (c) keeps Spring's default
                        // ChangeSessionIdAuthenticationStrategy in the composite — which is
                        // what rotates the session id on successful form login and prevents
                        // session-fixation attacks. We do NOT call
                        // .sessionAuthenticationStrategy(...) explicitly because that would
                        // replace the composite with just our supplied strategy, dropping the
                        // fixation protection. (-1 = unlimited concurrent sessions; we don't
                        // gate on count, only need the registry/filter.)
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(
                                new PathAwareSessionExpiredStrategy(apiAuthenticationEntryPoint, LOGIN_PATH)));

        http.authorizeHttpRequests(auth -> {
            // Anonymous: login, logout, error page, static assets needed to render the form.
            // ADR-037 §2: the SPA shell (`/`, `/index.html`) and SPA client-side routes are
            // NOT anonymous — Spring's entry point sends an unauthenticated browser through
            // /login first, and the request cache restores the original URL after login.
            auth.requestMatchers(LOGIN_PATH, LOGOUT_PATH)
                    .permitAll()
                    .requestMatchers(
                            HttpMethod.GET,
                            "/favicon.ico",
                            "/favicon.svg",
                            "/manifest.json",
                            "/robots.txt",
                            "/assets/**",
                            "/static/**")
                    .permitAll();
            ApiPathMatrix.applySharedRules(auth, properties);
            // Everything else — root, /index.html, and SPA client routes — requires a session.
            // Spring's DelegatingAuthenticationEntryPoint redirects to /login for non-API
            // paths and emits JSON 401 for /api/v1/** (covered by the shared matrix above).
            auth.anyRequest().authenticated();
        });

        return http.build();
    }
}
