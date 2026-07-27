package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.domain.identity.state.RolePermissionAssignmentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(name = "role_permission_assignment")
public class RolePermissionAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private IdentityRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80, updatable = false)
    private PermissionKey permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RolePermissionAssignmentState state = RolePermissionAssignmentState.ACTIVE;

    protected RolePermissionAssignment() {}

    public RolePermissionAssignment(IdentityRole role, PermissionKey permission) {
        if (role == null || permission == null) {
            throw new DomainValidationException(
                    "Role permission assignment requires role and permission",
                    "invalid_role_permission_assignment",
                    Map.of());
        }
        this.role = role;
        this.permission = permission;
    }

    public void revoke() {
        state = RolePermissionAssignmentState.REVOKED;
    }

    public IdentityRole getRole() {
        return role;
    }

    public PermissionKey getPermission() {
        return permission;
    }

    public RolePermissionAssignmentState getState() {
        return state;
    }
}
