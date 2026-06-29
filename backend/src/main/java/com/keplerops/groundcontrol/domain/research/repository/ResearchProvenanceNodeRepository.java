package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provenance nodes for a research run (ADR-069 §2). Run-scoped, plus a project-scoped graph read. */
public interface ResearchProvenanceNodeRepository extends JpaRepository<ResearchProvenanceNode, UUID> {

    List<ResearchProvenanceNode> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    /**
     * Project-scoped status read for the mixed-graph projection (ADR-070 §3 / §6).
     * Scoping is on the owning run's project so a project-blind read can never
     * surface another project's provenance nodes (GC-TM-009 / GC-RS-009).
     */
    @Query("SELECT n FROM ResearchProvenanceNode n "
            + "WHERE n.researchRun.project.id = :projectId AND n.status = :status")
    List<ResearchProvenanceNode> findByProjectIdAndStatus(
            @Param("projectId") UUID projectId, @Param("status") ProvenanceRecordStatus status);

    List<ResearchProvenanceNode> findByResearchRunIdAndStatusOrderByCreatedAtAsc(
            UUID researchRunId, ProvenanceRecordStatus status);

    Optional<ResearchProvenanceNode> findByResearchRunIdAndIdempotencyKey(UUID researchRunId, String idempotencyKey);

    Optional<ResearchProvenanceNode> findByIdAndResearchRunId(UUID id, UUID researchRunId);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);
}
