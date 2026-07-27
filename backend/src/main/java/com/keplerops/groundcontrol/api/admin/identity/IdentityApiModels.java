package com.keplerops.groundcontrol.api.admin.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class IdentityApiModels {

    private IdentityApiModels() {}

    public record IdentityPermissionResponse(String key, String description, boolean projectCapable) {
        static IdentityPermissionResponse from(PermissionKey permission) {
            return new IdentityPermissionResponse(
                    permission.name(), permission.getDescription(), permission.isProjectCapable());
        }
    }

    public record IdentityPermissionCatalogResponse(int catalogVersion, List<IdentityPermissionResponse> permissions) {
        static IdentityPermissionCatalogResponse current() {
            return new IdentityPermissionCatalogResponse(
                    PermissionKey.CATALOG_VERSION,
                    Arrays.stream(PermissionKey.values())
                            .map(IdentityPermissionResponse::from)
                            .toList());
        }
    }

    public record IdentityCreateUserRequest(
            @NotBlank @Size(max = 64) String loginName,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull IdentityUserKind kind) {}

    public record IdentityUpdateUserRequest(@Size(min = 1, max = 200) String displayName, IdentityUserState state) {
        @JsonIgnore
        @AssertTrue(message = "at least one update field is required") public boolean isNotEmpty() {
            return displayName != null || state != null;
        }
    }

    public record IdentityUserResponse(
            UUID id,
            String loginName,
            String displayName,
            IdentityUserKind kind,
            IdentityUserState state,
            Instant createdAt,
            Instant updatedAt) {
        static IdentityUserResponse from(IdentityUser user) {
            return new IdentityUserResponse(
                    user.getId(),
                    user.getLoginName(),
                    user.getDisplayName(),
                    user.getKind(),
                    user.getState(),
                    user.getCreatedAt(),
                    user.getUpdatedAt());
        }
    }

    public record IdentityCreateGroupRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z][a-z0-9._-]{1,99}$") String name,
            @NotBlank @Size(max = 200) String displayName) {}

    public record IdentityUpdateGroupRequest(@Size(min = 1, max = 200) String displayName, IdentityGroupState state) {
        @JsonIgnore
        @AssertTrue(message = "at least one update field is required") public boolean isNotEmpty() {
            return displayName != null || state != null;
        }
    }

    public record IdentityGroupResponse(
            UUID id, String name, String displayName, IdentityGroupState state, Instant createdAt, Instant updatedAt) {
        static IdentityGroupResponse from(IdentityGroup group) {
            return new IdentityGroupResponse(
                    group.getId(),
                    group.getName(),
                    group.getDisplayName(),
                    group.getState(),
                    group.getCreatedAt(),
                    group.getUpdatedAt());
        }
    }

    public record IdentityCreateMembershipRequest(
            @NotNull UUID userId, @NotNull UUID groupId, Instant effectiveFrom, Instant effectiveUntil) {}

    public record IdentityMembershipResponse(
            UUID id,
            UUID userId,
            UUID groupId,
            GroupMembershipState state,
            Instant effectiveFrom,
            Instant effectiveUntil) {
        static IdentityMembershipResponse from(GroupMembership membership) {
            return new IdentityMembershipResponse(
                    membership.getId(),
                    membership.getUser().getId(),
                    membership.getGroup().getId(),
                    membership.getState(),
                    membership.getEffectiveFrom(),
                    membership.getEffectiveUntil());
        }
    }

    public record IdentityCreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String key,
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 1000) String description) {}

    public record IdentityUpdateRoleRequest(
            @Size(min = 1, max = 200) String displayName,
            @Size(max = 1000) String description,
            IdentityRoleState state) {
        @JsonIgnore
        @AssertTrue(message = "at least one update field is required") public boolean isNotEmpty() {
            return displayName != null || description != null || state != null;
        }
    }

    public record IdentityRoleResponse(
            UUID id,
            String key,
            String displayName,
            String description,
            boolean builtIn,
            IdentityRoleState state,
            Instant createdAt,
            Instant updatedAt) {
        static IdentityRoleResponse from(IdentityRole role) {
            return new IdentityRoleResponse(
                    role.getId(),
                    role.getKey(),
                    role.getDisplayName(),
                    role.getDescription(),
                    role.isBuiltIn(),
                    role.getState(),
                    role.getCreatedAt(),
                    role.getUpdatedAt());
        }
    }

    public record IdentityAssignPermissionRequest(@NotNull UUID roleId, @NotNull PermissionKey permission) {}

    public record IdentityRolePermissionResponse(
            UUID id, UUID roleId, PermissionKey permission, RolePermissionAssignmentState state) {
        static IdentityRolePermissionResponse from(RolePermissionAssignment assignment) {
            return new IdentityRolePermissionResponse(
                    assignment.getId(),
                    assignment.getRole().getId(),
                    assignment.getPermission(),
                    assignment.getState());
        }
    }

    public record IdentityCreateRoleGrantRequest(
            @NotNull UUID roleId, UUID userId, UUID groupId, Instant effectiveFrom, Instant effectiveUntil) {
        @JsonIgnore
        @AssertTrue(message = "exactly one of userId or groupId is required") public boolean isExactlyOneSubject() {
            return (userId == null) != (groupId == null);
        }
    }

    public record IdentityRoleGrantResponse(
            UUID id,
            UUID roleId,
            UUID userId,
            UUID groupId,
            UUID projectId,
            RoleGrantState state,
            Instant effectiveFrom,
            Instant effectiveUntil) {
        static IdentityRoleGrantResponse from(RoleGrant grant) {
            return new IdentityRoleGrantResponse(
                    grant.getId(),
                    grant.getRole().getId(),
                    grant.getUser() == null ? null : grant.getUser().getId(),
                    grant.getGroup() == null ? null : grant.getGroup().getId(),
                    grant.getProjectId(),
                    grant.getState(),
                    grant.getEffectiveFrom(),
                    grant.getEffectiveUntil());
        }
    }

    public record IdentityCreateProjectAccessGrantRequest(
            UUID userId, UUID groupId, Instant effectiveFrom, Instant effectiveUntil) {
        @JsonIgnore
        @AssertTrue(message = "exactly one of userId or groupId is required") public boolean isExactlyOneSubject() {
            return (userId == null) != (groupId == null);
        }
    }

    public record IdentityProjectAccessGrantResponse(
            UUID id,
            UUID userId,
            UUID groupId,
            UUID projectId,
            ProjectAccessGrantState state,
            Instant effectiveFrom,
            Instant effectiveUntil) {
        static IdentityProjectAccessGrantResponse from(ProjectAccessGrant grant) {
            return new IdentityProjectAccessGrantResponse(
                    grant.getId(),
                    grant.getUser() == null ? null : grant.getUser().getId(),
                    grant.getGroup() == null ? null : grant.getGroup().getId(),
                    grant.getProjectId(),
                    grant.getState(),
                    grant.getEffectiveFrom(),
                    grant.getEffectiveUntil());
        }
    }
}
