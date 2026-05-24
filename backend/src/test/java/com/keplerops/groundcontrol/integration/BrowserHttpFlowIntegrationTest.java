package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.shared.security.SecurityProperties;
import com.keplerops.groundcontrol.shared.security.service.UserAdminService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.TestPropertySource;

/**
 * Browser-flow regression guard, using a real HTTP client + real servlet container.
 *
 * <p>Issue #846 surfaced a class of bug that MockMvc cannot catch: a Spring {@code forward:}
 * re-runs the security filter chain on the forwarded path. If the forwarded path is gated, the
 * entry point redirects back to the original URL and the loop closes at the HTTP layer.
 * MockMvc's {@code MockRequestDispatcher.forward()} only captures the forward target via
 * {@code getForwardedUrl()} — it never re-dispatches, so a loop-shaped configuration looks
 * green in unit-style tests.
 *
 * <p>{@link TestRestTemplate} drives a real Tomcat from a random port and exercises the same
 * filter chain a browser would, so a regression of that class fails here loudly. Redirects are
 * NOT followed by default so each hop's status / Location can be asserted directly; the
 * round-trip test follows redirects explicitly when it needs to.
 *
 * <p>Companion to {@link BrowserSessionIntegrationTest} (which keeps the MockMvc-fast tests
 * for chain semantics that don't depend on container dispatching). The two classes overlap on
 * a few assertions intentionally — each is the canonical record for its own failure mode.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
class BrowserHttpFlowIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_USERNAME = "admin-flow-alice";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private JdbcUserDetailsManager userDetailsManager;

    private TestRestTemplate http;

    @BeforeEach
    void setUp() {
        if (!userDetailsManager.userExists(ADMIN_USERNAME)) {
            userAdminService.createUser(ADMIN_USERNAME, ADMIN_PASSWORD, SecurityProperties.Role.ADMIN);
        }
        // SimpleClientHttpRequestFactory's HttpURLConnection follows redirects by default,
        // which would mask a redirect-loop bug by surfacing as "too many redirects" instead
        // of the chain we're asserting. Override prepareConnection to disable per-request
        // following so each hop's status / Location is visible.
        SimpleClientHttpRequestFactory noFollow = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection conn, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(conn, httpMethod);
                conn.setInstanceFollowRedirects(false);
            }
        };
        this.http = new TestRestTemplate(new RestTemplateBuilder().requestFactory(() -> noFollow));
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void anonymousRootRedirectsToLogin() {
        ResponseEntity<String> resp = http.getForEntity(url("/"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).asString().contains("/login");
    }

    @Test
    void anonymousIndexHtmlRedirectsToLogin() {
        ResponseEntity<String> resp = http.getForEntity(url("/index.html"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).asString().contains("/login");
    }

    @Test
    void anonymousMainAssetRedirectsToLogin() {
        ResponseEntity<String> resp = http.getForEntity(url("/assets/main-test.js"), String.class);
        assertThat(resp.getStatusCode())
                .as("ADR-037 §2: /assets/** carries the main SPA bundle and must NOT be anonymous")
                .isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation()).asString().contains("/login");
    }

    @Test
    void anonymousGetLoginReturnsLoginBundleNotARedirect() {
        // The regression class this test exists to catch: pre-issue-#846-fix, GET /login
        // returned 302 to /login (forward to /index.html → authenticated() → entry point →
        // redirect to /login → infinite loop). The fix returns 200 with the login bundle body.
        ResponseEntity<String> resp = http.getForEntity(url("/login"), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /login must return the login bundle, not redirect (would loop)")
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).asString().startsWith("text/html");
        assertThat(resp.getBody())
                .contains("gc-test-marker: login bundle")
                .doesNotContain("gc-test-marker: main spa shell");
    }

    @Test
    void anonymousLoginBundleAssetIsReachable() {
        ResponseEntity<String> resp = http.getForEntity(url("/login-assets/login-test.js"), String.class);
        assertThat(resp.getStatusCode())
                .as("Login bundle assets must be anonymously reachable so the React login UI can mount")
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("gc-test-marker: login bundle js");
    }

    @Test
    void fullLoginRoundTripReachesAuthenticatedSpaShell() {
        // 1. Prime the CSRF cookie via GET /login.
        ResponseEntity<String> loginPage = http.getForEntity(url("/login"), String.class);
        assertThat(loginPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = loginPage.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies)
                .as("GET /login must set the XSRF-TOKEN cookie via CookieCsrfTokenRepository")
                .isNotNull();
        Map<String, String> cookies = new LinkedHashMap<>();
        addCookies(setCookies, cookies);
        String csrf = cookies.get(CSRF_COOKIE);
        assertThat(csrf)
                .as("XSRF-TOKEN cookie must be present after GET /login")
                .isNotBlank();

        // 2. POST form-encoded credentials with CSRF.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        loginHeaders.add(HttpHeaders.COOKIE, renderCookieHeader(cookies));
        loginHeaders.add(CSRF_HEADER, csrf);
        String form = "username=" + ADMIN_USERNAME + "&password=" + ADMIN_PASSWORD;
        ResponseEntity<String> loginPost =
                http.exchange(url("/login"), HttpMethod.POST, new HttpEntity<>(form, loginHeaders), String.class);
        assertThat(loginPost.getStatusCode())
                .as("Successful form login must redirect (not 200, not 401)")
                .isEqualTo(HttpStatus.FOUND);
        URI loginTarget = loginPost.getHeaders().getLocation();
        assertThat(loginTarget).asString().doesNotContain("error");
        addCookies(loginPost.getHeaders().get(HttpHeaders.SET_COOKIE), cookies);

        // 3. Follow the redirect to / with the session cookie. Anonymous would 302 here.
        HttpHeaders followHeaders = new HttpHeaders();
        followHeaders.add(HttpHeaders.COOKIE, renderCookieHeader(cookies));
        URI rootTarget = loginTarget == null ? URI.create(url("/")) : URI.create(url(loginTarget.getPath()));
        ResponseEntity<String> shell =
                http.exchange(rootTarget, HttpMethod.GET, new HttpEntity<>(followHeaders), String.class);
        assertThat(shell.getStatusCode())
                .as("Authenticated GET / must serve the SPA shell, not redirect")
                .isEqualTo(HttpStatus.OK);
        assertThat(shell.getBody())
                .as("Authenticated SPA shell must include the main bundle marker, not the login marker")
                .contains("gc-test-marker: main spa shell")
                .doesNotContain("gc-test-marker: login bundle");
    }

    // --- cookie helpers (TestRestTemplate has no cookie jar of its own) -----------------

    private static void addCookies(List<String> setCookieHeaders, Map<String, String> jar) {
        if (setCookieHeaders == null) {
            return;
        }
        for (String header : setCookieHeaders) {
            // "<name>=<value>; Path=/; ..."
            String firstPair = Arrays.stream(header.split(";"))
                    .map(String::trim)
                    .findFirst()
                    .orElse("");
            int eq = firstPair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            jar.put(firstPair.substring(0, eq), firstPair.substring(eq + 1));
        }
    }

    private static String renderCookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return new String(sb.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
