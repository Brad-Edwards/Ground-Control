package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.RolePermissionAssignment;
import com.keplerops.groundcontrol.domain.identity.state.RolePermissionAssignmentState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionAssignmentRepository extends JpaRepository<RolePermissionAssignment, UUID> {
    List<RolePermissionAssignment> findByRoleIdAndState(UUID roleId, RolePermissionAssignmentState state);
}
