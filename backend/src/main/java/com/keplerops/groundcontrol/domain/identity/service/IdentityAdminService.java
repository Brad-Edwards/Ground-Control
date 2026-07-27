package com.keplerops.groundcontrol.domain.identity.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
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
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.identity.state.RolePermissionAssignmentState;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IdentityAdminService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdminService.class);

    private final IdentityUserRepository users;
    private final IdentityGroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final IdentityRoleRepository roles;
    private final RolePermissionAssignmentRepository permissions;
    private final RoleGrantRepository roleGrants;
    private final ProjectAccessGrantRepository projectAccessGrants;
    private final ProjectService projects;
    private final IdentityAdministrationPolicy policy;
    private final LastEffectiveAdministratorGuard lastAdminGuard;

    public IdentityAdminService(
            IdentityUserRepository users,
            IdentityGroupRepository groups,
            GroupMembershipRepository memberships,
            IdentityRoleRepository roles,
            RolePermissionAssignmentRepository permissions,
            RoleGrantRepository roleGrants,
            ProjectAccessGrantRepository projectAccessGrants,
            ProjectService projects,
            IdentityAdministrationPolicy policy,
            LastEffectiveAdministratorGuard lastAdminGuard) {
        this.users = users;
        this.groups = groups;
        this.memberships = memberships;
        this.roles = roles;
        this.permissions = permissions;
        this.roleGrants = roleGrants;
        this.projectAccessGrants = projectAccessGrants;
        this.projects = projects;
        this.policy = policy;
        this.lastAdminGuard = lastAdminGuard;
    }

    public IdentityUser createUser(IdentityCommands.CreateUser command) {
        policy.requireIdentityAdministration();
        if (users.existsByLoginName(command.loginName())) {
            throw new ConflictException("Identity login already exists: " + command.loginName());
        }
        var saved = users.save(new IdentityUser(command.loginName(), command.displayName(), command.kind()));
        log.info("identity_user_created: id={} login_name={}", saved.getId(), saved.getLoginName());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<IdentityUser> listUsers(Pageable pageable) {
        policy.requireIdentityAdministration();
        return users.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public IdentityUser getUser(UUID id) {
        policy.requireIdentityAdministration();
        return requireUser(id);
    }

    public IdentityUser updateUser(UUID id, IdentityCommands.UpdateUser command) {
        policy.requireIdentityAdministration();
        var user = requireUser(id);
        Runnable mutation = () -> {
            if (command.displayName() != null) {
                user.setDisplayName(command.displayName());
            }
            if (command.state() != null) {
                user.transitionTo(command.state());
            }
            users.save(user);
        };
        if (command.state() != null && command.state() != IdentityUserState.ACTIVE) {
            lastAdminGuard.protect(mutation);
        } else {
            mutation.run();
        }
        log.info("identity_user_updated: id={} state={}", id, user.getState());
        return user;
    }

    public IdentityGroup createGroup(IdentityCommands.CreateGroup command) {
        policy.requireIdentityAdministration();
        if (groups.existsByName(command.name())) {
            throw new ConflictException("Identity group already exists: " + command.name());
        }
        var saved = groups.save(new IdentityGroup(command.name(), command.displayName()));
        log.info("identity_group_created: id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<IdentityGroup> listGroups(Pageable pageable) {
        policy.requireIdentityAdministration();
        return groups.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public IdentityGroup getGroup(UUID id) {
        policy.requireIdentityAdministration();
        return requireGroup(id);
    }

    public IdentityGroup updateGroup(UUID id, IdentityCommands.UpdateGroup command) {
        policy.requireIdentityAdministration();
        var group = requireGroup(id);
        Runnable mutation = () -> {
            if (command.displayName() != null) {
                group.setDisplayName(command.displayName());
            }
            if (command.state() != null) {
                group.transitionTo(command.state());
            }
            groups.save(group);
        };
        if (command.state() == IdentityGroupState.INACTIVE) {
            lastAdminGuard.protect(mutation);
        } else {
            mutation.run();
        }
        return group;
    }

    public GroupMembership createMembership(IdentityCommands.CreateMembership command) {
        policy.requireIdentityAdministration();
        return memberships.save(new GroupMembership(
                requireUser(command.userId()),
                requireGroup(command.groupId()),
                command.effectiveFrom(),
                command.effectiveUntil()));
    }

    @Transactional(readOnly = true)
    public Page<GroupMembership> listMemberships(Pageable pageable) {
        policy.requireIdentityAdministration();
        return memberships.findAll(pageable);
    }

    public GroupMembership revokeMembership(UUID id) {
        policy.requireIdentityAdministration();
        var membership =
                memberships.findById(id).orElseThrow(() -> new NotFoundException("Group membership not found: " + id));
        lastAdminGuard.protect(() -> {
            membership.revoke();
            memberships.save(membership);
        });
        return membership;
    }

    public IdentityRole createRole(IdentityCommands.CreateRole command) {
        policy.requireIdentityAdministration();
        if (roles.existsByKey(command.key())) {
            throw new ConflictException("Identity role already exists: " + command.key());
        }
        var role = new IdentityRole(command.key(), command.displayName());
        role.setDescription(command.description());
        return roles.save(role);
    }

    @Transactional(readOnly = true)
    public Page<IdentityRole> listRoles(Pageable pageable) {
        policy.requireIdentityAdministration();
        return roles.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public IdentityRole getRole(UUID id) {
        policy.requireIdentityAdministration();
        return requireRole(id);
    }

    public IdentityRole updateRole(UUID id, IdentityCommands.UpdateRole command) {
        policy.requireIdentityAdministration();
        var role = requireRole(id);
        Runnable mutation = () -> {
            if (command.displayName() != null) {
                role.setDisplayName(command.displayName());
            }
            if (command.description() != null) {
                role.setDescription(command.description());
            }
            if (command.state() != null) {
                role.transitionTo(command.state());
            }
            roles.save(role);
        };
        if (command.state() == IdentityRoleState.INACTIVE) {
            lastAdminGuard.protect(mutation);
        } else {
            mutation.run();
        }
        return role;
    }

    public RolePermissionAssignment assignPermission(UUID roleId, PermissionKey permission) {
        policy.requireIdentityAdministration();
        policy.requirePermissionDelegation(permission, null);
        return permissions.save(new RolePermissionAssignment(requireRole(roleId), permission));
    }

    @Transactional(readOnly = true)
    public Page<RolePermissionAssignment> listPermissions(Pageable pageable) {
        policy.requireIdentityAdministration();
        return permissions.findAll(pageable);
    }

    public RolePermissionAssignment revokePermission(UUID id) {
        policy.requireIdentityAdministration();
        var assignment = permissions
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Role permission assignment not found: " + id));
        lastAdminGuard.protect(() -> {
            assignment.revoke();
            permissions.save(assignment);
        });
        return assignment;
    }

    public RoleGrant createRoleGrant(IdentityCommands.CreateRoleGrant command) {
        policy.requireIdentityAdministration();
        requireExactlyOneSubject(command.userId(), command.groupId());
        var role = requireRole(command.roleId());
        permissions.findByRoleIdAndState(role.getId(), RolePermissionAssignmentState.ACTIVE).stream()
                .map(RolePermissionAssignment::getPermission)
                .forEach(permission -> policy.requirePermissionDelegation(permission, command.projectId()));
        var project = command.projectId() == null ? null : projects.getById(command.projectId());
        RoleGrant grant = command.userId() != null
                ? RoleGrant.forUser(
                        role, requireUser(command.userId()), project, command.effectiveFrom(), command.effectiveUntil())
                : RoleGrant.forGroup(
                        role,
                        requireGroup(command.groupId()),
                        project,
                        command.effectiveFrom(),
                        command.effectiveUntil());
        return roleGrants.save(grant);
    }

    @Transactional(readOnly = true)
    public Page<RoleGrant> listRoleGrants(Pageable pageable) {
        policy.requireIdentityAdministration();
        return roleGrants.findAll(pageable);
    }

    public RoleGrant revokeRoleGrant(UUID id) {
        policy.requireIdentityAdministration();
        var grant = roleGrants.findById(id).orElseThrow(() -> new NotFoundException("Role grant not found: " + id));
        lastAdminGuard.protect(() -> {
            grant.revoke();
            roleGrants.save(grant);
        });
        return grant;
    }

    public ProjectAccessGrant createProjectAccessGrant(IdentityCommands.CreateProjectAccessGrant command) {
        policy.requireIdentityAdministration();
        requireExactlyOneSubject(command.userId(), command.groupId());
        policy.requireProjectAccessDelegation(command.projectId());
        var project = projects.getById(command.projectId());
        ProjectAccessGrant grant = command.userId() != null
                ? ProjectAccessGrant.forUser(
                        requireUser(command.userId()), project, command.effectiveFrom(), command.effectiveUntil())
                : ProjectAccessGrant.forGroup(
                        requireGroup(command.groupId()), project, command.effectiveFrom(), command.effectiveUntil());
        return projectAccessGrants.save(grant);
    }

    @Transactional(readOnly = true)
    public Page<ProjectAccessGrant> listProjectAccessGrants(Pageable pageable) {
        policy.requireIdentityAdministration();
        return projectAccessGrants.findAll(pageable);
    }

    public ProjectAccessGrant revokeProjectAccessGrant(UUID id) {
        policy.requireIdentityAdministration();
        var grant = projectAccessGrants
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Project access grant not found: " + id));
        lastAdminGuard.protect(() -> {
            grant.revoke();
            projectAccessGrants.save(grant);
        });
        return grant;
    }

    private IdentityUser requireUser(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("Identity user not found: " + id));
    }

    private IdentityGroup requireGroup(UUID id) {
        return groups.findById(id).orElseThrow(() -> new NotFoundException("Identity group not found: " + id));
    }

    private IdentityRole requireRole(UUID id) {
        return roles.findById(id).orElseThrow(() -> new NotFoundException("Identity role not found: " + id));
    }

    private static void requireExactlyOneSubject(UUID userId, UUID groupId) {
        if ((userId == null) == (groupId == null)) {
            throw new DomainValidationException("Exactly one of userId or groupId is required");
        }
    }
}
