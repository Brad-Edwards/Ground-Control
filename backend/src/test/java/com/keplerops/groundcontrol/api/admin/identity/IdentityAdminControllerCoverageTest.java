package com.keplerops.groundcontrol.api.admin.identity;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.identity.model.GroupMembership;
import com.keplerops.groundcontrol.domain.identity.model.IdentityGroup;
import com.keplerops.groundcontrol.domain.identity.model.IdentityRole;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import com.keplerops.groundcontrol.domain.identity.model.RoleGrant;
import com.keplerops.groundcontrol.domain.identity.model.RolePermissionAssignment;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class IdentityAdminControllerCoverageTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID EDGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final Instant FROM = Instant.parse("2026-07-27T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void everyIdentityAdministrationRouteMapsCommandsAndResponses() {
        var service = mock(IdentityAdminService.class);
        var projects = mock(ProjectService.class);
        var page = Pageable.unpaged();
        var user = identified(new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN), USER_ID);
        var group = identified(new IdentityGroup("operators", "Operators"), GROUP_ID);
        var role = identified(new IdentityRole("IDENTITY_OPERATOR", "Identity operator"), ROLE_ID);
        var project = identified(new Project("ground-control", "Ground Control"), PROJECT_ID);
        var membership = identified(new GroupMembership(user, group, FROM, UNTIL), EDGE_ID);
        var permission = identified(new RolePermissionAssignment(role, PermissionKey.IDENTITY_ADMIN), EDGE_ID);
        var userRoleGrant = identified(RoleGrant.forUser(role, user, project, FROM, UNTIL), EDGE_ID);
        var groupRoleGrant = identified(RoleGrant.forGroup(role, group, null, FROM, UNTIL), EDGE_ID);
        var userProjectGrant = identified(ProjectAccessGrant.forUser(user, project, FROM, UNTIL), EDGE_ID);
        var groupProjectGrant = identified(ProjectAccessGrant.forGroup(group, project, FROM, UNTIL), EDGE_ID);

        when(service.createUser(any())).thenReturn(user);
        when(service.listUsers(page)).thenReturn(new PageImpl<>(List.of(user)));
        when(service.getUser(USER_ID)).thenReturn(user);
        when(service.updateUser(any(), any())).thenReturn(user);
        when(service.createGroup(any())).thenReturn(group);
        when(service.listGroups(page)).thenReturn(new PageImpl<>(List.of(group)));
        when(service.getGroup(GROUP_ID)).thenReturn(group);
        when(service.updateGroup(any(), any())).thenReturn(group);
        when(service.createMembership(any())).thenReturn(membership);
        when(service.listMemberships(page)).thenReturn(new PageImpl<>(List.of(membership)));
        when(service.revokeMembership(EDGE_ID)).thenReturn(membership);
        when(service.createRole(any())).thenReturn(role);
        when(service.listRoles(page)).thenReturn(new PageImpl<>(List.of(role)));
        when(service.getRole(ROLE_ID)).thenReturn(role);
        when(service.updateRole(any(), any())).thenReturn(role);
        when(service.assignPermission(ROLE_ID, PermissionKey.IDENTITY_ADMIN)).thenReturn(permission);
        when(service.listPermissions(page)).thenReturn(new PageImpl<>(List.of(permission)));
        when(service.revokePermission(EDGE_ID)).thenReturn(permission);
        when(projects.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.createRoleGrant(any())).thenReturn(userRoleGrant);
        when(service.listRoleGrants(page)).thenReturn(new PageImpl<>(List.of(userRoleGrant, groupRoleGrant)));
        when(service.revokeRoleGrant(EDGE_ID)).thenReturn(groupRoleGrant);
        when(service.createProjectAccessGrant(any())).thenReturn(userProjectGrant);
        when(service.listProjectAccessGrants(page))
                .thenReturn(new PageImpl<>(List.of(userProjectGrant, groupProjectGrant)));
        when(service.revokeProjectAccessGrant(EDGE_ID)).thenReturn(groupProjectGrant);

        var users = new IdentityUserController(service);
        assertThat(users.create(new IdentityApiModels.IdentityCreateUserRequest(
                                "alice", "Alice", IdentityUserKind.HUMAN))
                        .id())
                .isEqualTo(USER_ID);
        assertThat(users.list(page).getTotalElements()).isOne();
        assertThat(users.get(USER_ID).loginName()).isEqualTo("alice");
        assertThat(users.update(
                                USER_ID,
                                new IdentityApiModels.IdentityUpdateUserRequest("Alice A", IdentityUserState.ACTIVE))
                        .displayName())
                .isEqualTo("Alice");

        var groups = new IdentityGroupController(service);
        assertThat(groups.create(new IdentityApiModels.IdentityCreateGroupRequest("operators", "Operators"))
                        .id())
                .isEqualTo(GROUP_ID);
        assertThat(groups.list(page).getTotalElements()).isOne();
        assertThat(groups.get(GROUP_ID).name()).isEqualTo("operators");
        assertThat(groups.update(
                                GROUP_ID,
                                new IdentityApiModels.IdentityUpdateGroupRequest(
                                        "Operators A", IdentityGroupState.ACTIVE))
                        .state())
                .isEqualTo(IdentityGroupState.ACTIVE);

        var memberships = new GroupMembershipController(service);
        assertThat(memberships
                        .create(new IdentityApiModels.IdentityCreateMembershipRequest(USER_ID, GROUP_ID, FROM, UNTIL))
                        .groupId())
                .isEqualTo(GROUP_ID);
        assertThat(memberships.list(page).getTotalElements()).isOne();
        assertThat(memberships.revoke(EDGE_ID).userId()).isEqualTo(USER_ID);

        var roles = new IdentityRoleController(service);
        assertThat(roles.create(new IdentityApiModels.IdentityCreateRoleRequest(
                                "IDENTITY_OPERATOR", "Identity operator", "Administers identity"))
                        .id())
                .isEqualTo(ROLE_ID);
        assertThat(roles.list(page).getTotalElements()).isOne();
        assertThat(roles.get(ROLE_ID).key()).isEqualTo("IDENTITY_OPERATOR");
        assertThat(roles.update(
                                ROLE_ID,
                                new IdentityApiModels.IdentityUpdateRoleRequest(
                                        "Identity admin", "Administers identity", IdentityRoleState.ACTIVE))
                        .state())
                .isEqualTo(IdentityRoleState.ACTIVE);

        var rolePermissions = new RolePermissionController(service);
        assertThat(rolePermissions
                        .assign(new IdentityApiModels.IdentityAssignPermissionRequest(
                                ROLE_ID, PermissionKey.IDENTITY_ADMIN))
                        .permission())
                .isEqualTo(PermissionKey.IDENTITY_ADMIN);
        assertThat(rolePermissions.list(page).getTotalElements()).isOne();
        assertThat(rolePermissions.revoke(EDGE_ID).roleId()).isEqualTo(ROLE_ID);

        var roleGrants = new RoleGrantController(service, projects);
        assertThat(roleGrants
                        .create(
                                new IdentityApiModels.IdentityCreateRoleGrantRequest(
                                        ROLE_ID, USER_ID, null, FROM, UNTIL),
                                "ground-control")
                        .projectId())
                .isEqualTo(PROJECT_ID);
        assertThat(roleGrants.list(page).getContent())
                .extracting(IdentityApiModels.IdentityRoleGrantResponse::userId)
                .containsExactly(USER_ID, null);
        assertThat(roleGrants.revoke(EDGE_ID).groupId()).isEqualTo(GROUP_ID);

        var projectAccess = new ProjectAccessGrantController(service, projects);
        assertThat(projectAccess
                        .create(
                                new IdentityApiModels.IdentityCreateProjectAccessGrantRequest(
                                        USER_ID, null, FROM, UNTIL),
                                "ground-control")
                        .projectId())
                .isEqualTo(PROJECT_ID);
        assertThat(projectAccess.list(page).getContent())
                .extracting(IdentityApiModels.IdentityProjectAccessGrantResponse::groupId)
                .containsExactly(null, GROUP_ID);
        assertThat(projectAccess.revoke(EDGE_ID).groupId()).isEqualTo(GROUP_ID);

        assertThat(new IdentityPermissionController().list().catalogVersion()).isEqualTo(PermissionKey.CATALOG_VERSION);
    }

    @Test
    void requestSubjectAndPatchValidatorsCoverValidAndInvalidShapes() {
        assertThat(new IdentityApiModels.IdentityUpdateUserRequest(null, null).isNotEmpty())
                .isFalse();
        assertThat(new IdentityApiModels.IdentityUpdateUserRequest("Alice", null).isNotEmpty())
                .isTrue();
        assertThat(new IdentityApiModels.IdentityUpdateGroupRequest(null, null).isNotEmpty())
                .isFalse();
        assertThat(new IdentityApiModels.IdentityUpdateGroupRequest(null, IdentityGroupState.ACTIVE).isNotEmpty())
                .isTrue();
        assertThat(new IdentityApiModels.IdentityUpdateRoleRequest(null, null, null).isNotEmpty())
                .isFalse();
        assertThat(new IdentityApiModels.IdentityUpdateRoleRequest(null, "Description", null).isNotEmpty())
                .isTrue();
        assertThat(new IdentityApiModels.IdentityCreateRoleGrantRequest(ROLE_ID, USER_ID, null, null, null)
                        .isExactlyOneSubject())
                .isTrue();
        assertThat(new IdentityApiModels.IdentityCreateRoleGrantRequest(ROLE_ID, USER_ID, GROUP_ID, null, null)
                        .isExactlyOneSubject())
                .isFalse();
        assertThat(new IdentityApiModels.IdentityCreateProjectAccessGrantRequest(null, GROUP_ID, null, null)
                        .isExactlyOneSubject())
                .isTrue();
        assertThat(new IdentityApiModels.IdentityCreateProjectAccessGrantRequest(null, null, null, null)
                        .isExactlyOneSubject())
                .isFalse();
    }

    private static <T> T identified(T entity, UUID id) {
        setField(entity, "id", id);
        setField(entity, "createdAt", FROM);
        setField(entity, "updatedAt", FROM);
        return entity;
    }
}
