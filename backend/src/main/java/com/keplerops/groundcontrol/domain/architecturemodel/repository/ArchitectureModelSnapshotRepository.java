package com.keplerops.groundcontrol.domain.architecturemodel.repository;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchitectureModelSnapshotRepository extends JpaRepository<ArchitectureModelSnapshot, UUID> {

    boolean existsByProjectIdAndModelVersion(UUID projectId, String modelVersion);

    Optional<ArchitectureModelSnapshot> findByIdAndProjectId(UUID id, UUID projectId);

    List<ArchitectureModelSnapshot> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
