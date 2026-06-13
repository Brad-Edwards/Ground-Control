package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DerivationRunRepository extends JpaRepository<DerivationRun, UUID> {

    Optional<DerivationRun> findByIdAndProjectId(UUID id, UUID projectId);

    List<DerivationRun> findByProjectIdOrderByRequestedAtDesc(UUID projectId);
}
