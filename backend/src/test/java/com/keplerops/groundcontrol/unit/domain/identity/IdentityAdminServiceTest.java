package com.keplerops.groundcontrol.unit.domain.identity;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.model.GroupMembership;
import com.keplerops.groundcontrol.domain.identity.model.IdentityGroup;
import com.keplerops.groundcontrol.domain.identity.model.IdentityRole;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import com.keplerops.groundcontrol.domain.identity.model.RoleGrant;
import com.keplerops.groundcontrol.domain.identity.model.RolePermissionAssignment;
import com.keplerops.groundcontrol.domain.identity.repository.GroupMembershipRepository;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityGroupRepository;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityRoleRepository;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityUserRepository;
import com.keplerops.groundcontrol.domain.identity.repository.ProjectAccessGrantRepository;
import com.keplerops.groundcontrol.domain.identity.repository.RoleGrantRepository;
import com.keplerops.groundcontrol.domain.identity.repository.RolePermissionAssignmentRepository;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAdministrationPolicy;
import com.keplerops.groundcontrol.domain.identity.service.IdentityCommands;
import com.keplerops.groundcontrol.domain.identity.service.LastEffectiveAdministratorGuard;
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityAdminServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID EDGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");

    @Mock
    private IdentityUserRepository users;

    @Mock
    private IdentityGroupRepository groups;

    @Mock
    private GroupMembershipRepository memberships;

    @Mock
    private IdentityRoleRepository roles;

    @Mock
    private RolePermissionAssignmentRepository permissions;

    @Mock
    private RoleGrantRepository roleGrants;

    @Mock
    private ProjectAccessGrantRepository projectAccessGrants;

    @Mock
    private ProjectService projects;

    @Mock
    private IdentityAdministrationPolicy policy;

    @Mock
    private LastEffectiveAdministratorGuard lastAdminGuard;

    private IdentityAdminService service;

    private IdentityUser user;
    private IdentityGroup group;
    private IdentityRole role;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new IdentityAdminService(
                users,
                groups,
                memberships,
                roles,
                permissions,
                roleGrants,
                projectAccessGrants,
                projects,
                policy,
                lastAdminGuard);
        user = identified(new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN), USER_ID);
        group = identified(new IdentityGroup("operators", "Operators"), GROUP_ID);
        role = identified(new IdentityRole("IDENTITY_OPERATOR", "Identity operator"), ROLE_ID);
        project = identified(new Project("ground-control", "Ground Control"), PROJECT_ID);
        lenient()
                .doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(lastAdminGuard)
                .protect(any(Runnable.class));
    }

    @Test
    void permissionAssignmentChecksDelegationBeforeWriting() {
        when(roles.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(permissions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var assignment = service.assignPermission(ROLE_ID, PermissionKey.PROJECT_WRITE);

        verify(policy).requirePermissionDelegation(PermissionKey.PROJECT_WRITE, null);
        assertThat(assignment.getPermission()).isEqualTo(PermissionKey.PROJECT_WRITE);
    }

    @Test
    void projectScopedRoleGrantChecksEveryBundledPermissionAtThatProject() {
        when(roles.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(projects.getById(PROJECT_ID)).thenReturn(project);
        when(permissions.findByRoleIdAndState(
                        ROLE_ID,
                        com.keplerops.groundcontrol.domain.identity.state.RolePermissionAssignmentState.ACTIVE))
                .thenReturn(List.of(
                        new RolePermissionAssignment(role, PermissionKey.PROJECT_READ),
                        new RolePermissionAssignment(role, PermissionKey.PROJECT_WRITE)));
        when(roleGrants.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createRoleGrant(new IdentityCommands.CreateRoleGrant(ROLE_ID, USER_ID, null, PROJECT_ID, null, null));

        verify(policy).requirePermissionDelegation(PermissionKey.PROJECT_READ, PROJECT_ID);
        verify(policy).requirePermissionDelegation(PermissionKey.PROJECT_WRITE, PROJECT_ID);
    }

    @Test
    void everyAccessRemovingMutationUsesTheSharedLastAdminGuard() {
        var membership = identified(new GroupMembership(user, group, null, null), EDGE_ID);
        var assignment = identified(new RolePermissionAssignment(role, PermissionKey.IDENTITY_ADMIN), EDGE_ID);
        var roleGrant = identified(RoleGrant.forUser(role, user, null, null, null), EDGE_ID);
        var projectGrant = identified(ProjectAccessGrant.forUser(user, project, null, null), EDGE_ID);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(roles.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(memberships.findById(EDGE_ID)).thenReturn(Optional.of(membership));
        when(permissions.findById(EDGE_ID)).thenReturn(Optional.of(assignment));
        when(roleGrants.findById(EDGE_ID)).thenReturn(Optional.of(roleGrant));
        when(projectAccessGrants.findById(EDGE_ID)).thenReturn(Optional.of(projectGrant));

        service.updateUser(USER_ID, new IdentityCommands.UpdateUser(null, IdentityUserState.SUSPENDED));
        service.updateGroup(GROUP_ID, new IdentityCommands.UpdateGroup(null, IdentityGroupState.INACTIVE));
        service.updateRole(ROLE_ID, new IdentityCommands.UpdateRole(null, null, IdentityRoleState.INACTIVE));
        service.revokeMembership(EDGE_ID);
        service.revokePermission(EDGE_ID);
        service.revokeRoleGrant(EDGE_ID);
        service.revokeProjectAccessGrant(EDGE_ID);

        verify(lastAdminGuard, times(7)).protect(any(Runnable.class));
    }

    @Test
    void roleGrantRejectsAnAmbiguousSubjectAtTheServiceBoundary() {
        assertThatThrownBy(() -> service.createRoleGrant(
                        new IdentityCommands.CreateRoleGrant(ROLE_ID, USER_ID, GROUP_ID, null, null, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Exactly one");
    }

    private static <T> T identified(T entity, UUID id) {
        setField(entity, "id", id);
        return entity;
    }
}
