package com.keplerops.groundcontrol.domain.controls.repository;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlRepository extends JpaRepository<Control, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    Optional<Control> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<Control> findByProjectIdAndUid(UUID projectId, String uid);

    List<Control> findByProjectIdAndUidIn(UUID projectId, Collection<String> uids);

    List<Control> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
