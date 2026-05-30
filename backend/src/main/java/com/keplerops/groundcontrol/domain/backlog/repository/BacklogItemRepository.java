package com.keplerops.groundcontrol.domain.backlog.repository;

import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BacklogItemRepository extends JpaRepository<BacklogItem, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<BacklogItem> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<BacklogItem> findByProjectIdAndUid(UUID projectId, String uid);

    List<BacklogItem> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
