package com.keplerops.groundcontrol.domain.derivation.repository;

import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemModelFactRepository extends JpaRepository<SystemModelFact, UUID> {

    List<SystemModelFact> findByProjectIdOrderByDerivedAtDesc(UUID projectId);

    List<SystemModelFact> findByProjectIdAndFactKindOrderByDerivedAtDesc(UUID projectId, SystemModelFactKind factKind);

    List<SystemModelFact> findByProjectIdAndDerivationRunIdOrderByDerivedAtDesc(UUID projectId, UUID derivationRunId);

    List<SystemModelFact> findByProjectIdAndDerivationRunIdAndFactKindOrderByDerivedAtDesc(
            UUID projectId, UUID derivationRunId, SystemModelFactKind factKind);
}
