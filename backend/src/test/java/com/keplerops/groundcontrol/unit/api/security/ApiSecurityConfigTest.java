package com.keplerops.groundcontrol.unit.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.shared.security.ApiSecurityConfig;
import com.keplerops.groundcontrol.shared.security.BrowserSecurityConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice unit tests for {@link ApiSecurityConfig}. Loads only the config under test plus a tiny
 * stub controller, so the {@code SecurityFilterChain} is wired (and exercised by
 * {@code MockMvc}) without bringing in the JPA / Testcontainers stack of
 * {@code ApiSecurityIntegrationTest}. This lets the chain contribute to JaCoCo unit-test
 * coverage (the SonarCloud {@code new_coverage} metric).
 */
class ApiSecurityConfigTest {

    @RestController
    static class StubController {
        @GetMapping("/api/v1/echo")
        String echo() {
            return "echo-ok";
        }

        @GetMapping("/api/v1/admin/echo")
        String adminEcho() {
            return "admin-ok";
        }

        @GetMapping("/api/v1/embeddings/echo")
        String embeddingsEcho() {
            return "embeddings-ok";
        }

        @GetMapping("/api/v1/analysis/sweep/echo")
        String sweepEcho() {
            return "sweep-ok";
        }

        @GetMapping("/api/v1/pack-registry/echo")
        String packRegistryEcho() {
            return "pack-registry-ok";
        }

        @GetMapping("/api/v1/trust-policies/echo")
        String trustEcho() {
            return "trust-ok";
        }

        @GetMapping("/api/v1/pack-install-records/echo")
        String installEcho() {
            return "install-ok";
        }

        // MCP tool-usage telemetry: GET reads under the prefix are admin-only, POST capture is
        // open to any authenticated session (issue #1104). Fake "/echo" paths so the stub does not
        // collide with the real McpTelemetryController mappings when the full context loads.
        @GetMapping("/api/v1/mcp-tool-usage/echo")
        String mcpToolUsageRead() {
            return "mcp-usage-ok";
        }

        @PostMapping("/api/v1/mcp-tool-usage/echo")
        String mcpToolUsageCapture() {
            return "mcp-capture-ok";
        }

        @GetMapping("/")
        String spaShell() {
            return "spa-shell";
        }
    }

    @Nested
    @WebMvcTest(controllers = StubController.class)
    @Import({ApiSecurityConfig.class, BrowserSecurityConfig.class, StubController.class, StubJdbcBeans.class})
    @TestPropertySource(
            properties = {
                "groundcontrol.security.enabled=true",
                "groundcontrol.security.openapi-public=false",
                "groundcontrol.security.credentials[0].principal-name=alice",
                "groundcontrol.security.credentials[0].token=user-token-aaa",
                "groundcontrol.security.credentials[0].role=USER",
                "groundcontrol.security.credentials[1].principal-name=admin-bob",
                "groundcontrol.security.credentials[1].token=admin-token-bbb",
                "groundcontrol.security.credentials[1].role=ADMIN"
            })
    class WithSecurityEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void anonymousApiV1_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/echo"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("authentication_required"));
        }

        @Test
        void userTokenOnApiV1_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/echo").header("Authorization", "Bearer user-token-aaa"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("echo-ok"));
        }

        @Test
        void userTokenOnAdminPath_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/admin/echo").header("Authorization", "Bearer user-token-aaa"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("access_denied"));
        }

        @Test
        void adminTokenOnAdminPath_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/admin/echo").header("Authorization", "Bearer admin-token-bbb"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("admin-ok"));
        }

        @ParameterizedTest(name = "[{index}] USER on {0} returns 403")
        @ValueSource(
                strings = {
                    "/api/v1/embeddings/echo",
                    "/api/v1/analysis/sweep/echo",
                    "/api/v1/pack-registry/echo",
                    "/api/v1/trust-policies/echo",
                    "/api/v1/pack-install-records/echo",
                    "/api/v1/mcp-tool-usage/echo"
                })
        void userTokenOnAdminPath_returns403(String adminPath) throws Exception {
            mockMvc.perform(get(adminPath).header("Authorization", "Bearer user-token-aaa"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void adminTokenOnMcpToolUsageRead_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/mcp-tool-usage/echo").header("Authorization", "Bearer admin-token-bbb"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("mcp-usage-ok"));
        }

        @Test
        void userTokenOnMcpToolUsageCapture_returns200() throws Exception {
            // The capture write must stay reachable by any authenticated session; only GET reads
            // under the prefix are admin-gated.
            mockMvc.perform(post("/api/v1/mcp-tool-usage/echo").header("Authorization", "Bearer user-token-aaa"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("mcp-capture-ok"));
        }

        @Test
        void anonymousSpaShell_redirectsToLogin() throws Exception {
            // ADR-037 §2: the SPA shell is no longer anonymously served. An unauthenticated
            // browser navigating to "/" must be sent through /login first; after a successful
            // form login Spring's request cache restores the original URL.
            mockMvc.perform(get("/"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                    result.getResponse().getRedirectedUrl())
                            .endsWith("/login"));
        }

        @Test
        void invalidBearerToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/echo").header("Authorization", "Bearer not-a-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("authentication_required"));
        }

        @Test
        void wrongScheme_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/echo").header("Authorization", "Basic dXNlcjpwYXNz"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @WebMvcTest(controllers = StubController.class)
    @Import({ApiSecurityConfig.class, BrowserSecurityConfig.class, StubController.class, StubJdbcBeans.class})
    @TestPropertySource(properties = {"groundcontrol.security.enabled=false"})
    class WithSecurityDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void anonymousApiV1_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/echo"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("echo-ok"));
        }

        @Test
        void anonymousAdminPath_returns200() throws Exception {
            mockMvc.perform(get("/api/v1/admin/echo"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("admin-ok"));
        }
    }

    @Nested
    @WebMvcTest(controllers = StubController.class)
    @Import({ApiSecurityConfig.class, BrowserSecurityConfig.class, StubController.class, StubJdbcBeans.class})
    @TestPropertySource(
            properties = {
                "groundcontrol.security.enabled=true",
                "groundcontrol.security.openapi-public=true",
                "groundcontrol.security.credentials[0].principal-name=alice",
                "groundcontrol.security.credentials[0].token=user-token-aaa",
                "groundcontrol.security.credentials[0].role=USER"
            })
    class WithOpenApiPublic {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void apiV1_stillRequiresAuth() throws Exception {
            mockMvc.perform(get("/api/v1/echo")).andExpect(status().isUnauthorized());
        }
    }

    /**
     * Test-only stand-ins for the JDBC beans normally produced by {@link BrowserSecurityConfig}.
     * The slice doesn't need real user storage — the bearer chain doesn't touch it — but the
     * configuration class declares {@code DataSource}-backed bean methods, and Spring needs
     * something to satisfy those types.
     *
     * <p>{@code @TestConfiguration} (not plain {@code @Configuration}) so this stub config is
     * excluded from Spring Boot's default component scan — otherwise its mock {@code DataSource}
     * leaks into every {@code @SpringBootTest} (notably {@code BrowserSessionIntegrationTest}),
     * where Flyway then crashes trying to connect through it.
     */
    @TestConfiguration
    static class StubJdbcBeans {

        // Renamed to *Stub so {@code @ConditionalOnMissingBean} on
        // {@link BrowserSecurityConfig#userDetailsManager}/{@code userAdminJdbcTemplate} sees a
        // bean of the same type and skips its own definition. Without rename Spring Boot's
        // default "no bean override" guard surfaces a {@code BeanDefinitionOverrideException}.

        @Bean
        DataSource dataSourceStub() {
            return Mockito.mock(DataSource.class);
        }

        @Bean
        JdbcUserDetailsManager userDetailsManagerStub() {
            return Mockito.mock(JdbcUserDetailsManager.class);
        }

        @Bean
        JdbcTemplate jdbcTemplateStub() {
            return Mockito.mock(JdbcTemplate.class);
        }
    }
}
