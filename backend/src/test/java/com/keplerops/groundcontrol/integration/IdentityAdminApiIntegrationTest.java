package com.keplerops.groundcontrol.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class IdentityAdminApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void nonSecretIdentityAdministrationLifecycleRoundTripsThroughRest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/identity/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value(1))
                .andExpect(jsonPath("$.permissions[?(@.key == 'PROJECT_READ')]").exists());

        UUID userId = createdId(
                postJson(
                        "/api/v1/admin/identity/users",
                        """
                {"loginName":"api-user","displayName":"API user","kind":"HUMAN"}
                """));
        mockMvc.perform(get("/api/v1/admin/identity/users").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].loginName").value("api-user"));
        mockMvc.perform(get("/api/v1/admin/identity/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
        patchJson(
                        "/api/v1/admin/identity/users/" + userId,
                        """
                        {"displayName":"API user renamed"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("API user renamed"));

        UUID groupId = createdId(postJson(
                "/api/v1/admin/identity/groups",
                """
                {"name":"api-operators","displayName":"API operators"}
                """));
        mockMvc.perform(get("/api/v1/admin/identity/groups").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("api-operators"));
        mockMvc.perform(get("/api/v1/admin/identity/groups/" + groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId.toString()));
        patchJson(
                        "/api/v1/admin/identity/groups/" + groupId,
                        """
                        {"displayName":"API operators renamed"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("API operators renamed"));

        UUID membershipId = createdId(postJson(
                "/api/v1/admin/identity/memberships",
                """
                {"userId":"%s","groupId":"%s"}
                """.formatted(userId, groupId)));
        mockMvc.perform(get("/api/v1/admin/identity/memberships").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(membershipId.toString()));

        UUID roleId = createdId(
                postJson(
                        "/api/v1/admin/identity/roles",
                        """
                {"key":"API_PROJECT_READER","displayName":"API project reader"}
                """));
        mockMvc.perform(get("/api/v1/admin/identity/roles").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id == '%s')]".formatted(roleId)).exists());
        mockMvc.perform(get("/api/v1/admin/identity/roles/" + roleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(roleId.toString()));
        patchJson(
                        "/api/v1/admin/identity/roles/" + roleId,
                        """
                        {"description":"Project read role"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Project read role"));

        UUID permissionId = createdId(postJson(
                "/api/v1/admin/identity/role-permissions",
                """
                {"roleId":"%s","permission":"PROJECT_READ"}
                """.formatted(roleId)));
        mockMvc.perform(get("/api/v1/admin/identity/role-permissions").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(permissionId))
                        .exists());

        UUID roleGrantId = createdId(postJson(
                "/api/v1/admin/identity/role-grants",
                """
                {"roleId":"%s","groupId":"%s"}
                """.formatted(roleId, groupId)));
        mockMvc.perform(get("/api/v1/admin/identity/role-grants").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(roleGrantId.toString()));

        UUID projectAccessId = createdId(postJson(
                "/api/v1/admin/identity/project-access-grants?project=ground-control",
                """
                {"groupId":"%s"}
                """.formatted(groupId)));
        mockMvc.perform(get("/api/v1/admin/identity/project-access-grants").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(projectAccessId.toString()));

        revoke("/api/v1/admin/identity/project-access-grants/" + projectAccessId + "/revoke");
        revoke("/api/v1/admin/identity/role-grants/" + roleGrantId + "/revoke");
        revoke("/api/v1/admin/identity/role-permissions/" + permissionId + "/revoke");
        revoke("/api/v1/admin/identity/memberships/" + membershipId + "/revoke");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions patchJson(String path, String body) throws Exception {
        return mockMvc.perform(
                patch(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID createdId(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        String response =
                action.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return UUID.fromString(body.path("id").asText());
    }

    private void revoke(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVOKED"));
    }
}
