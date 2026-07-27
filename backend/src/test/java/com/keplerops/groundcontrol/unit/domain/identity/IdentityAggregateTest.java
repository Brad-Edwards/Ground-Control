package com.keplerops.groundcontrol.unit.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.model.GroupMembership;
import com.keplerops.groundcontrol.domain.identity.model.IdentityGroup;
import com.keplerops.groundcontrol.domain.identity.model.IdentityRole;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import com.keplerops.groundcontrol.domain.identity.model.RoleGrant;
import com.keplerops.groundcontrol.domain.identity.state.GroupMembershipState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.identity.state.ProjectAccessGrantState;
import com.keplerops.groundcontrol.domain.identity.state.RoleGrantState;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
}
