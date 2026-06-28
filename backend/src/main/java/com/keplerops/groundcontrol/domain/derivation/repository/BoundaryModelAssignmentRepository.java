package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoundaryModelAssignmentRepository extends JpaRepository<BoundaryModelAssignment, UUID> {

    List<BoundaryModelAssignment> findBySnapshotIdOrderBySourceFactKey(UUID snapshotId);
}
