package com.keplerops.groundcontrol.domain.architecturemodel.repository;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchitectureModelElementRepository extends JpaRepository<ArchitectureModelElement, UUID> {

    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    Optional<ArchitectureModelElement> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<ArchitectureModelElement> findByProjectIdAndStableKey(UUID projectId, String stableKey);

    List<ArchitectureModelElement> findByProjectIdOrderByStableKey(UUID projectId);
}
