package com.keplerops.groundcontrol.domain.identity.service;

import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import java.time.Instant;
import java.util.UUID;

public final class IdentityCommands {

    private IdentityCommands() {}

    public record CreateUser(String loginName, String displayName, IdentityUserKind kind) {}

    public record UpdateUser(String displayName, IdentityUserState state) {}

    public record CreateGroup(String name, String displayName) {}

    public record UpdateGroup(String displayName, IdentityGroupState state) {}

    public record CreateMembership(UUID userId, UUID groupId, Instant effectiveFrom, Instant effectiveUntil) {}

    public record CreateRole(String key, String displayName, String description) {}

    public record UpdateRole(String displayName, String description, IdentityRoleState state) {}

    public record CreateRoleGrant(
            UUID roleId, UUID userId, UUID groupId, UUID projectId, Instant effectiveFrom, Instant effectiveUntil) {}

    public record CreateProjectAccessGrant(
            UUID userId, UUID groupId, UUID projectId, Instant effectiveFrom, Instant effectiveUntil) {}
}
