package com.keplerops.groundcontrol.unit.domain.identity;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.model.GroupMembership;
import com.keplerops.groundcontrol.domain.identity.model.IdentityGroup;
import com.keplerops.groundcontrol.domain.identity.model.IdentityRole;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import com.keplerops.groundcontrol.domain.identity.model.RoleGrant;
import com.keplerops.groundcontrol.domain.identity.model.RolePermissionAssignment;
import com.keplerops.groundcontrol.domain.identity.state.GroupMembershipState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.identity.state.ProjectAccessGrantState;
import com.keplerops.groundcontrol.domain.identity.state.RoleGrantState;
import com.keplerops.groundcontrol.domain.identity.state.RolePermissionAssignmentState;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAggregateTest {

    @Test
    void identityUserUsesTheSharedNormalizedLoginContract() {
        assertThatThrownBy(() -> new IdentityUser("Alice", "Alice", IdentityUserKind.HUMAN))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("login");
    }

    @Test
    void disabledIdentityUserCannotBeReactivated() {
        var user = new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN);

        user.transitionTo(IdentityUserState.DISABLED);

        assertThatThrownBy(() -> user.transitionTo(IdentityUserState.ACTIVE))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("DISABLED");
    }

    @Test
    void accessBearingAggregatesRevokeInsteadOfHardDelete() {
        var user = new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN);
        var group = new IdentityGroup("operators", "Operators");
        var role = new IdentityRole("PROJECT_READER", "Project reader");
        var project = new Project("identity-test", "Identity test");

        var membership = new GroupMembership(user, group, null, null);
        var roleGrant = RoleGrant.forGroup(role, group, project, null, null);
        var projectGrant = ProjectAccessGrant.forUser(user, project, null, null);

        membership.revoke();
        roleGrant.revoke();
        projectGrant.revoke();

        assertThat(membership.getState()).isEqualTo(GroupMembershipState.REVOKED);
        assertThat(roleGrant.getState()).isEqualTo(RoleGrantState.REVOKED);
        assertThat(projectGrant.getState()).isEqualTo(ProjectAccessGrantState.REVOKED);
        assertThat(group.getState()).isEqualTo(IdentityGroupState.ACTIVE);
        assertThat(role.getState()).isEqualTo(IdentityRoleState.ACTIVE);
    }

    @Test
    void roleGrantFactoriesSetExactlyOneSubject() {
        var user = new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN);
        var group = new IdentityGroup("operators", "Operators");
        var role = new IdentityRole("IDENTITY_OPERATOR", "Identity operator");

        var userGrant = RoleGrant.forUser(role, user, null, null, null);
        var groupGrant = RoleGrant.forGroup(role, group, null, null, null);

        assertThat(userGrant.getUser()).isSameAs(user);
        assertThat(userGrant.getGroup()).isNull();
        assertThat(groupGrant.getUser()).isNull();
        assertThat(groupGrant.getGroup()).isSameAs(group);
    }

    @Test
    void aggregateValuesAndEffectiveWindowsAreExposedForAuditResponses() {
        var from = Instant.parse("2026-07-27T00:00:00Z");
        var until = Instant.parse("2026-08-27T00:00:00Z");
        var user = new IdentityUser("alice", " Alice ", IdentityUserKind.HUMAN);
        var group = new IdentityGroup("operators", " Operators ");
        var role = new IdentityRole("PROJECT_READER", " Project reader ");
        role.setDescription("Reads projects");
        var project = new Project("identity-test", "Identity test");
        var projectId = UUID.fromString("00000000-0000-0000-0000-000000000104");
        setField(project, "id", projectId);
        var membership = new GroupMembership(user, group, from, until);
        var assignment = new RolePermissionAssignment(role, PermissionKey.PROJECT_READ);
        var roleGrant = RoleGrant.forUser(role, user, project, from, until);
        var projectGrant = ProjectAccessGrant.forGroup(group, project, from, until);

        assignment.revoke();

        assertThat(user.getLoginName()).isEqualTo("alice");
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.getKind()).isEqualTo(IdentityUserKind.HUMAN);
        assertThat(group.getName()).isEqualTo("operators");
        assertThat(group.getDisplayName()).isEqualTo("Operators");
        assertThat(role.getKey()).isEqualTo("PROJECT_READER");
        assertThat(role.getDisplayName()).isEqualTo("Project reader");
        assertThat(role.getDescription()).isEqualTo("Reads projects");
        assertThat(role.isBuiltIn()).isFalse();
        assertThat(membership.getUser()).isSameAs(user);
        assertThat(membership.getGroup()).isSameAs(group);
        assertThat(membership.getEffectiveFrom()).isEqualTo(from);
        assertThat(membership.getEffectiveUntil()).isEqualTo(until);
        assertThat(assignment.getRole()).isSameAs(role);
        assertThat(assignment.getPermission()).isEqualTo(PermissionKey.PROJECT_READ);
        assertThat(assignment.getState()).isEqualTo(RolePermissionAssignmentState.REVOKED);
        assertThat(roleGrant.getRole()).isSameAs(role);
        assertThat(roleGrant.getProject()).isSameAs(project);
        assertThat(roleGrant.getProjectId()).isEqualTo(projectId);
        assertThat(roleGrant.getEffectiveFrom()).isEqualTo(from);
        assertThat(roleGrant.getEffectiveUntil()).isEqualTo(until);
        assertThat(projectGrant.getProject()).isSameAs(project);
        assertThat(projectGrant.getProjectId()).isEqualTo(projectId);
        assertThat(projectGrant.getEffectiveFrom()).isEqualTo(from);
        assertThat(projectGrant.getEffectiveUntil()).isEqualTo(until);

        setField(roleGrant, "project", null);
        setField(projectGrant, "project", null);
        assertThat(roleGrant.getProjectId()).isEqualTo(projectId);
        assertThat(projectGrant.getProjectId()).isEqualTo(projectId);
    }

    @Test
    void invalidAggregateShapesAreRejectedAtConstruction() {
        var user = new IdentityUser("alice", "Alice", IdentityUserKind.HUMAN);
        var group = new IdentityGroup("operators", "Operators");
        var role = new IdentityRole("PROJECT_READER", "Project reader");
        var project = new Project("identity-test", "Identity test");
        var from = Instant.parse("2026-07-27T00:00:00Z");

        assertThatThrownBy(() -> new IdentityUser("alice", " ", IdentityUserKind.HUMAN))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityUser("alice", "Alice", null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> user.setDisplayName(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> user.transitionTo(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityGroup("Operators", "Operators"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityGroup("operators", " ")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> group.transitionTo(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityRole("reader", "Reader")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityRole("PROJECT_READER", " ")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> role.transitionTo(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new GroupMembership(null, group, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new GroupMembership(user, group, from, from))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("effectiveUntil");
        assertThatThrownBy(() -> new RolePermissionAssignment(null, PermissionKey.PROJECT_READ))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> RoleGrant.forUser(null, user, null, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> RoleGrant.forGroup(role, null, null, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectAccessGrant.forUser(user, null, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectAccessGrant.forGroup(null, project, null, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
