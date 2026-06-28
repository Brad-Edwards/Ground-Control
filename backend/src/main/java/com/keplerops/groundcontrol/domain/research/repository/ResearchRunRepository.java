package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Project-scoped queries for {@link ResearchRun}. Every lookup is scoped by
 * project id so a project-blind read can never return another project's runs
 * (GC-TM-009 / GC-RS-009).
 */
public interface ResearchRunRepository extends JpaRepository<ResearchRun, UUID> {

    Optional<ResearchRun> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<ResearchRun> findByProjectIdAndUid(UUID projectId, String uid);

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    List<ResearchRun> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
