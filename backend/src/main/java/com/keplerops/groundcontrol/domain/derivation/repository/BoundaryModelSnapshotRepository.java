package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoundaryModelSnapshotRepository extends JpaRepository<BoundaryModelSnapshot, UUID> {

    Optional<BoundaryModelSnapshot> findByProjectIdAndDerivationRunId(UUID projectId, UUID derivationRunId);
}
