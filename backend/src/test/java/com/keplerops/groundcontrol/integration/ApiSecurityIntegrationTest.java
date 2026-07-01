package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end coverage of the API access control layer wired by
 * {@link com.keplerops.groundcontrol.shared.security.ApiSecurityConfig}.
 *
 * <p>Flips {@code groundcontrol.security.enabled=true} for this test only via
 * {@link TestPropertySource}; the rest of the integration suite continues to run with security
 * disabled (default for the {@code test} profile). Asserts the cardinal contracts of issue #243:
 * anonymous requests rejected, USER cannot reach admin paths, ADMIN can, actuator probes are
 * anonymous, OpenAPI is gated, and the IP allowlist denies out-of-range traffic.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "groundcontrol.security.enabled=true",
            "groundcontrol.security.openapi-public=false",
            "groundcontrol.security.credentials[0].principal-name=alice",
            "groundcontrol.security.credentials[0].token=user-token-aaa",
            "groundcontrol.security.credentials[0].role=USER",
            "groundcontrol.security.credentials[1].principal-name=admin-bob",
            "groundcontrol.security.credentials[1].token=admin-token-bbb",
            "groundcontrol.security.credentials[1].role=ADMIN",
            "groundcontrol.security.ip-allowlist[0]=127.0.0.0/8",
            "groundcontrol.security.ip-allowlist[1]=::1/128"
        })
class ApiSecurityIntegrationTest extends BaseIntegrationTest {

    private static final String USER_TOKEN = "Bearer user-token-aaa";
    private static final String ADMIN_TOKEN = "Bearer admin-token-bbb";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousReadOfApiV1_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/requirements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", is("authentication_required")))
                .andExpect(jsonPath("$.error.message", notNullValue()));
    }

    @Test
    void userTokenReadingApiV1_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/requirements").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void userTokenHittingAdminPath_returns403() throws Exception {
        var file = new MockMultipartFile(
                "file", "test.sdoc", "text/plain", "[[SECTION]]\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/admin/import/strictdoc").file(file).header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("access_denied")));
    }

    @Test
    void adminTokenHittingAdminPath_passesAuthLayer() throws Exception {
        // The strictdoc import will succeed or fail with a downstream parse status. Either way,
        // the request must clear the auth/authz layer — i.e., NOT 401 and NOT 403.
        var file = new MockMultipartFile(
                "file", "test.sdoc", "text/plain", "[[SECTION]]\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/admin/import/strictdoc").file(file).header("Authorization", ADMIN_TOKEN))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("Admin token rejected at auth layer; status=" + status + " body="
                                + result.getResponse().getContentAsString());
                    }
                });
    }

    @Test
    void userTokenCreatingCampaign_returns403() throws Exception {
        // GC-S005: campaign writes configure/enable credentialed outbound collection and are admin-only.
        // Authz runs before the controller, so the empty body never reaches validation — the gate denies
        // the non-admin first.
        mockMvc.perform(post("/api/v1/evidence-campaigns")
                        .param("project", "ground-control")
                        .contentType("application/json")
                        .content("{}")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("access_denied")));
    }

    @Test
    void userTokenListingCampaigns_passesAuthLayer() throws Exception {
        // Reads stay open to any authenticated project member — must not be 401 or 403.
        mockMvc.perform(get("/api/v1/evidence-campaigns")
                        .param("project", "ground-control")
                        .header("Authorization", USER_TOKEN))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("User token rejected reading campaigns; status=" + status);
                    }
                });
    }

    @Test
    void actuatorHealth_isAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void openApiSchema_requiresAuth_whenOpenapiPublicFalse() throws Exception {
        mockMvc.perform(get("/api/openapi.json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", is("authentication_required")));
    }

    @Test
    void openApiSchema_userTokenAccepted() throws Exception {
        mockMvc.perform(get("/api/openapi.json").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void ipAllowlist_blocksOutOfRangeSourceWith403() throws Exception {
        mockMvc.perform(get("/api/v1/requirements")
                        .header("Authorization", USER_TOKEN)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.42");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", is("access_denied")));
    }

    @Test
    void invalidBearerToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/requirements").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", is("authentication_required")));
    }

    @Test
    void wrongScheme_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/requirements").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }
}
