package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.shared.security.service.UserAdminService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end coverage for the ADR-037 browser-session login chain.
 *
 * <p>Flips {@code groundcontrol.security.enabled=true} for this test so both filter chains
 * actively gate traffic, the way they will in production. The bearer-chain assertions live in
 * {@code ApiSecurityIntegrationTest}; this test focuses on the additive surface — form login,
 * CSRF, /api/v1/** XHRs through the session, logout, and the bearer chain still working
 * alongside the session chain (no regression).
 *
 * <p>{@link MockMvc} does not run through Tomcat's servlet container, so the session cookie's
 * name / HttpOnly / SameSite hardening (configured via
 * {@code server.servlet.session.cookie.*}) cannot be asserted here. Session continuity is
 * verified through {@link MockHttpSession} attributes — the canonical MockMvc pattern — which
 * is the same mechanism Spring's session-management uses to thread the SecurityContext.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "groundcontrol.security.enabled=true",
            "groundcontrol.security.openapi-public=false",
            "groundcontrol.security.credentials[0].principal-name=agent-bob",
            "groundcontrol.security.credentials[0].token=bearer-token-aaaaaaaaaaaa",
            "groundcontrol.security.credentials[0].role=USER",
            "groundcontrol.security.ip-allowlist[0]=127.0.0.0/8",
            "groundcontrol.security.ip-allowlist[1]=::1/128",
            "server.servlet.session.cookie.secure=false",
        })
class BrowserSessionIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_USERNAME = "admin-alice";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private JdbcUserDetailsManager userDetailsManager;

    @BeforeEach
    void seedAdminUser() {
        if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
            userAdminService.createUser(
                    ADMIN_USERNAME,
                    ADMIN_PASSWORD,
                    com.keplerops.groundcontrol.shared.security.SecurityProperties.Role.ADMIN);
        }
    }

    @Test
    void anonymousApiCall_returnsJson401NotRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/requirements"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .as("Anonymous /api/v1/** XHR must receive a JSON envelope, not a /login redirect")
                        .startsWith("application/json"))
                .andExpect(result ->
                        assertThat(result.getResponse().getRedirectedUrl()).isNull());
    }

    @Test
    void loginPage_isAnonymouslyReachable() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void formLoginIssuesSessionAndUnblocksApiAccess() throws Exception {
        AuthenticatedSession authenticated = loginAndCaptureSession();

        mockMvc.perform(get("/api/v1/requirements").session(authenticated.session))
                .andExpect(status().isOk());
    }

    @Test
    void bearerCallerStillWorks_evenWithBrowserChainPresent() throws Exception {
        mockMvc.perform(get("/api/v1/requirements").header("Authorization", "Bearer bearer-token-aaaaaaaaaaaa"))
                .andExpect(status().isOk());
    }

    @Test
    void csrfBlocksSessionMutationWithoutHeader() throws Exception {
        AuthenticatedSession authenticated = loginAndCaptureSession();

        mockMvc.perform(
                        post("/api/v1/admin/users")
                                .session(authenticated.session)
                                .contentType("application/json")
                                .content(
                                        "{\"username\":\"new-user\",\"password\":\"correct-horse-battery-staple\",\"role\":\"USER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfTokenAllowsSessionMutation() throws Exception {
        AuthenticatedSession authenticated = loginAndCaptureSession();
        Cookie csrf = freshCsrfFor(authenticated.session);

        mockMvc.perform(
                        post("/api/v1/admin/users")
                                .session(authenticated.session)
                                .contentType("application/json")
                                .cookie(csrf)
                                .header(CSRF_HEADER, csrf.getValue())
                                .content(
                                        "{\"username\":\"new-csrf-user\",\"password\":\"correct-horse-battery-staple\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void logoutClearsSessionAndReturns204() throws Exception {
        AuthenticatedSession authenticated = loginAndCaptureSession();
        Cookie csrf = freshCsrfFor(authenticated.session);

        mockMvc.perform(post("/logout")
                        .session(authenticated.session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isNoContent());

        // The session was invalidated; presenting it again must 401.
        mockMvc.perform(get("/api/v1/requirements").session(authenticated.session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotReuseSession() throws Exception {
        // Seed a separate USER-role principal so we don't disable the bootstrap admin (the
        // last-admin guard would refuse).
        String username = "user-revoke-test";
        String password = "correct-horse-battery-staple";
        if (!userDetailsManager.userExists(username)) {
            userAdminService.createUser(
                    username, password, com.keplerops.groundcontrol.shared.security.SecurityProperties.Role.USER);
        }

        // Log in as that user and confirm the session is live.
        MvcResult page = mockMvc.perform(get("/login")).andReturn();
        Cookie csrf = page.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .param("username", username)
                        .param("password", password)
                        .param("_csrf", csrf.getValue())
                        .session(session)
                        .cookie(csrf))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/v1/requirements").session(session)).andExpect(status().isOk());

        // Admin disables the user. ConcurrentSessionFilter must now reject the existing session.
        userAdminService.updateEnabled(username, false);

        mockMvc.perform(get("/api/v1/requirements").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .as("Expired session on an API path must return JSON, not a /login redirect")
                        .startsWith("application/json"));
        // Cleanup so subsequent tests do not see a disabled user lingering.
        userAdminService.updateEnabled(username, true);
    }

    @Test
    void loginPostWithoutCsrfIsRejected_evenWithoutPreExistingSession() throws Exception {
        // CookieCsrfTokenRepository writes the XSRF cookie on GET /login without creating a
        // server-side session, so a real browser's first POST /login arrives session-less.
        // The CSRF matcher must still require a token on that POST — otherwise an attacker
        // can host an auto-submitting form to /login with their own credentials and the
        // victim's browser would get a session for the attacker's principal (login-CSRF /
        // session-fixation by credential swap). This test pins that contract.
        mockMvc.perform(post("/login").param("username", ADMIN_USERNAME).param("password", ADMIN_PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCredentialsRejectedAtLogin() throws Exception {
        MvcResult loginPage = mockMvc.perform(get("/login")).andReturn();
        Cookie csrf = loginPage.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).as("Login page must publish an XSRF cookie").isNotNull();

        mockMvc.perform(post("/login")
                        .param("username", ADMIN_USERNAME)
                        .param("password", "wrong-password-aaaa")
                        .param("_csrf", csrf.getValue())
                        .session(new MockHttpSession())
                        .cookie(csrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .as("Bad creds must bounce back to /login?error")
                        .contains("/login")
                        .contains("error"));
    }

    @Test
    void getLoginReturnsSpaShellNotSpringDefaultForm() throws Exception {
        // With .loginPage(LOGIN_PATH), Spring disables DefaultLoginPageGeneratingFilter. GET
        // /login reaches SpaController which issues a Servlet forward to /index.html (the SPA
        // shell).
        //
        // MockMvc's MockRequestDispatcher.forward() calls response.setForwardedUrl(path) and
        // returns — it does not re-dispatch to ResourceHttpRequestHandler (so body is empty in
        // tests). The forwarded URL IS captured by getForwardedUrl(). We use that as the
        // structural gate:
        //   - With DefaultLoginPageGeneratingFilter active: the filter writes HTML directly and
        //     calls return without invoking chain.doFilter(), so DispatcherServlet is never
        //     reached, no RequestDispatcher.forward() is called, and getForwardedUrl() is null.
        //   - After .loginPage(LOGIN_PATH): the filter is removed, SpaController handles the
        //     request, and getForwardedUrl() is "/index.html".
        MvcResult result =
                mockMvc.perform(get("/login")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getForwardedUrl())
                .as("GET /login must be forwarded to /index.html by SpaController; "
                        + "null means DefaultLoginPageGeneratingFilter is still active")
                .isEqualTo("/index.html");
    }

    @Test
    void postLoginFormEncodedStillAuthenticates() throws Exception {
        // UsernamePasswordAuthenticationFilter runs at loginProcessingUrl("/login") regardless
        // of whether DefaultLoginPageGeneratingFilter is disabled. A correct form POST must
        // still issue a session and redirect to the default success URL.
        MvcResult page = mockMvc.perform(get("/login")).andReturn();
        Cookie csrf = page.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).as("GET /login must still publish an XSRF cookie").isNotNull();

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .contentType("application/x-www-form-urlencoded")
                        .param("username", ADMIN_USERNAME)
                        .param("password", ADMIN_PASSWORD)
                        .param("_csrf", csrf.getValue())
                        .session(session)
                        .cookie(csrf))
                .andExpect(status().is3xxRedirection());

        // The session must now be authenticated — confirmed by a successful API call.
        mockMvc.perform(get("/api/v1/requirements").session(session)).andExpect(status().isOk());
    }

    private AuthenticatedSession loginAndCaptureSession() throws Exception {
        // GET /login is anonymous, so Spring's session policy (IF_REQUIRED) does NOT create a
        // session here — the CSRF token lives in the XSRF-TOKEN cookie via
        // CookieCsrfTokenRepository, not in the session. We allocate a fresh MockHttpSession
        // ourselves so POST /login has a session container for Spring's
        // SessionAuthenticationStrategy to migrate into on a successful login.
        MvcResult page = mockMvc.perform(get("/login")).andReturn();
        Cookie csrf = page.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).as("Login page must publish an XSRF cookie").isNotNull();

        MockHttpSession freshSession = new MockHttpSession();
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", ADMIN_USERNAME)
                        .param("password", ADMIN_PASSWORD)
                        .param("_csrf", csrf.getValue())
                        .session(freshSession)
                        .cookie(csrf))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(authenticated)
                .as("Successful form login must produce an authenticated session")
                .isNotNull();
        return new AuthenticatedSession(authenticated);
    }

    private Cookie freshCsrfFor(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/requirements").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = result.getResponse().getCookie(CSRF_COOKIE);
        // Spring may rotate the XSRF token on certain transitions; if the GET didn't issue a new
        // one, the prior cookie tied to this session is still valid. Returning a blank cookie
        // when none is present makes the failure mode obvious in the test (the assertion the
        // caller runs will surface "CSRF required" rather than NPE'ing on cookie.getValue()).
        return csrf != null ? csrf : new Cookie(CSRF_COOKIE, "");
    }

    private record AuthenticatedSession(MockHttpSession session) {}
}
