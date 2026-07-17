package com.keplerops.groundcontrol.domain.controls.repository;

import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlTestRepository extends JpaRepository<ControlTest, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<ControlTest> findByIdAndProjectId(UUID id, UUID projectId);

    List<ControlTest> findByProjectIdOrderByTestDateDesc(UUID projectId);

    List<ControlTest> findByProjectIdAndControlIdOrderByTestDateDesc(UUID projectId, UUID controlId);

    long countByProjectIdAndControlId(UUID projectId, UUID controlId);

    Optional<ControlTest> findByIdAndProjectIdAndControlId(UUID id, UUID projectId, UUID controlId);
}
