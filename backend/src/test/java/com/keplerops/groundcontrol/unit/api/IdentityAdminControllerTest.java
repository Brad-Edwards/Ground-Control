package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.admin.identity.GroupMembershipController;
import com.keplerops.groundcontrol.api.admin.identity.IdentityGroupController;
import com.keplerops.groundcontrol.api.admin.identity.IdentityPermissionController;
import com.keplerops.groundcontrol.api.admin.identity.IdentityRoleController;
import com.keplerops.groundcontrol.api.admin.identity.IdentityUserController;
import com.keplerops.groundcontrol.api.admin.identity.ProjectAccessGrantController;
import com.keplerops.groundcontrol.api.admin.identity.RoleGrantController;
import com.keplerops.groundcontrol.api.admin.identity.RolePermissionController;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest({
    IdentityPermissionController.class,
    IdentityUserController.class,
    IdentityGroupController.class,
    GroupMembershipController.class,
    IdentityRoleController.class,
    RolePermissionController.class,
    RoleGrantController.class,
    ProjectAccessGrantController.class
})
class IdentityAdminControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdentityAdminService service;

    @MockitoBean
    @SuppressWarnings("unused")
    private ProjectService projectService;

    @Test
    void permissionCatalogIsClosedAndVersioned() throws Exception {
        mockMvc.perform(get("/api/v1/admin/identity/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value(1))
                .andExpect(
                        jsonPath("$.permissions[?(@.key == 'IDENTITY_ADMIN')]").exists())
                .andExpect(jsonPath("$.permissions[?(@.key == 'PROJECT_READ')]").exists());
    }

    @Test
    void userListIsPageableRatherThanAnUnboundedSnapshot() throws Exception {
        when(service.listUsers(any())).thenReturn(new PageImpl<>(java.util.List.of(user())));

        mockMvc.perform(get("/api/v1/admin/identity/users").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].loginName").value("alice"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void createUserAcceptsNoCredentialOrActorFields() throws Exception {
        when(service.createUser(any())).thenReturn(user());

        mockMvc.perform(
                        post("/api/v1/admin/identity/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "loginName":"alice",
                                  "displayName":"Alice",
                                  "kind":"HUMAN"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));

        verify(service).createUser(any());
    }

    @Test
    void roleGrantRejectsRequestsWithBothUserAndGroupSubjects() throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/identity/role-grants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "roleId":"00000000-0000-0000-0000-000000000201",
                                  "userId":"00000000-0000-0000-0000-000000000101",
                                  "groupId":"00000000-0000-0000-0000-000000000301"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void userUpdateRejectsAnEmptyPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/identity/users/" + USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    private static IdentityUser user() {
        var user = new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN);
        setField(user, "id", USER_ID);
        setField(user, "createdAt", Instant.parse("2026-07-27T00:00:00Z"));
        setField(user, "updatedAt", Instant.parse("2026-07-27T00:00:00Z"));
        return user;
    }
}
