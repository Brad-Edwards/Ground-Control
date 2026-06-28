package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelGap;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoundaryModelGapRepository extends JpaRepository<BoundaryModelGap, UUID> {

    List<BoundaryModelGap> findBySnapshotIdOrderBySourceFactKey(UUID snapshotId);
}
