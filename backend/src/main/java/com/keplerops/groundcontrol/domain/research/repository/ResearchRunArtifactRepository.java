package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Artifact manifest rows for a research run (ADR-064 §6). */
public interface ResearchRunArtifactRepository extends JpaRepository<ResearchRunArtifact, UUID> {

    List<ResearchRunArtifact> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    /**
     * Project-scoped status read for the mixed-graph projection (ADR-070 §3 / §6).
     * Scoping is on the owning run's project so a project-blind read can never
     * surface another project's artifacts (GC-TM-009 / GC-RS-009).
     */
    @Query("SELECT a FROM ResearchRunArtifact a "
            + "WHERE a.researchRun.project.id = :projectId AND a.status = :status")
    List<ResearchRunArtifact> findByProjectIdAndStatus(
            @Param("projectId") UUID projectId, @Param("status") ResearchArtifactStatus status);

    Optional<ResearchRunArtifact> findByResearchRunIdAndIdempotencyKey(UUID researchRunId, String idempotencyKey);

    Optional<ResearchRunArtifact> findByResearchRunIdAndArtifactTypeAndStatus(
            UUID researchRunId, ResearchArtifactType artifactType, ResearchArtifactStatus status);

    int countByResearchRunIdAndArtifactTypeAndStatus(
            UUID researchRunId, ResearchArtifactType artifactType, ResearchArtifactStatus status);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);

    Optional<ResearchRunArtifact> findByIdAndResearchRunId(UUID id, UUID researchRunId);
}
