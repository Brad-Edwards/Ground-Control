package com.keplerops.groundcontrol.domain.dataclassification.repository;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLattice;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataClassificationLatticeRepository extends JpaRepository<DataClassificationLattice, UUID> {

    Optional<DataClassificationLattice> findByProjectId(UUID projectId);
}
