package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityAuthorizationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdentityUserRepository userRepository;

    @Autowired
    private IdentityGroupRepository groupRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    @Autowired
    private IdentityRoleRepository roleRepository;

    @Autowired
    private RolePermissionAssignmentRepository permissionRepository;

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Autowired
    private ProjectAccessGrantRepository projectAccessRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private IdentityAuthorizationService authorizationService;

    @Autowired
    private EntityManager entityManager;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    @Test
    void directGlobalGrantAuthorizesWithoutProjectAdmission() {
        var user = saveUser("direct-" + UUID.randomUUID());
        var role = saveRole("DIRECT_" + compactId());
        permissionRepository.save(new RolePermissionAssignment(role, PermissionKey.IDENTITY_ADMIN));
        roleGrantRepository.save(RoleGrant.forUser(role, user, null, null, null));
        entityManager.flush();

        assertThat(authorizationService.isAllowed(user.getId(), PermissionKey.IDENTITY_ADMIN, null))
                .isTrue();
    }

    @Test
    void groupProjectGrantRequiresIndependentGroupProjectAccess() {
        var user = saveUser("group-" + UUID.randomUUID());
        var group =
                groupRepository.save(new IdentityGroup("group-" + compactId().toLowerCase(Locale.ROOT), "Group"));
        var role = saveRole("READER_" + compactId());
        permissionRepository.save(new RolePermissionAssignment(role, PermissionKey.PROJECT_READ));
        membershipRepository.save(new GroupMembership(user, group, null, null));
        roleGrantRepository.save(RoleGrant.forGroup(role, group, project, null, null));
        entityManager.flush();

        assertThat(authorizationService.isAllowed(user.getId(), PermissionKey.PROJECT_READ, project.getId()))
                .isFalse();

        projectAccessRepository.save(ProjectAccessGrant.forGroup(group, project, null, null));
        entityManager.flush();

        assertThat(authorizationService.isAllowed(user.getId(), PermissionKey.PROJECT_READ, project.getId()))
                .isTrue();
    }

    @Test
    void revokingMembershipImmediatelyRemovesGroupDerivedAuthority() {
        var user = saveUser("revoked-" + UUID.randomUUID());
        var group =
                groupRepository.save(new IdentityGroup("group-" + compactId().toLowerCase(Locale.ROOT), "Group"));
        var role = saveRole("WRITER_" + compactId());
        permissionRepository.save(new RolePermissionAssignment(role, PermissionKey.PROJECT_WRITE));
        var membership = membershipRepository.save(new GroupMembership(user, group, null, null));
        roleGrantRepository.save(RoleGrant.forGroup(role, group, project, null, null));
        projectAccessRepository.save(ProjectAccessGrant.forGroup(group, project, null, null));
        entityManager.flush();

        assertThat(authorizationService.isAllowed(user.getId(), PermissionKey.PROJECT_WRITE, project.getId()))
                .isTrue();

        membership.revoke();
        membershipRepository.save(membership);
        entityManager.flush();

        assertThat(authorizationService.isAllowed(user.getId(), PermissionKey.PROJECT_WRITE, project.getId()))
                .isFalse();
    }

    private IdentityUser saveUser(String loginName) {
        return userRepository.save(new IdentityUser(loginName, loginName, IdentityUserKind.HUMAN));
    }

    private IdentityRole saveRole(String key) {
        return roleRepository.save(new IdentityRole(key, key));
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
