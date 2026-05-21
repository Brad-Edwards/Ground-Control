package com.keplerops.groundcontrol.domain.riskcontrol.repository;

import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScopedControlImplementationRepository extends JpaRepository<ScopedControlImplementation, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<ScopedControlImplementation> findByIdAndProjectId(UUID id, UUID projectId);

    List<ScopedControlImplementation> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<ScopedControlImplementation> findByProjectIdAndControlIdOrderByCreatedAtDesc(UUID projectId, UUID controlId);
}
