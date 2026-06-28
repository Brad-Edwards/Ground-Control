package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelBoundary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoundaryModelBoundaryRepository extends JpaRepository<BoundaryModelBoundary, UUID> {

    List<BoundaryModelBoundary> findBySnapshotIdOrderByBoundaryKey(UUID snapshotId);
}
