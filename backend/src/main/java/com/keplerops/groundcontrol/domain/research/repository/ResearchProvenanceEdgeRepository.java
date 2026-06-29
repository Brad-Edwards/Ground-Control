package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provenance edges for a research run (ADR-069 §2). Run-scoped, plus a project-scoped graph read. */
public interface ResearchProvenanceEdgeRepository extends JpaRepository<ResearchProvenanceEdge, UUID> {

    List<ResearchProvenanceEdge> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    /**
     * Project-scoped status read for the mixed-graph projection (ADR-070 §3 / §6).
     * Scoping is on the owning run's project so a project-blind read can never
     * surface another project's provenance edges (GC-TM-009 / GC-RS-009).
     */
    @Query("SELECT e FROM ResearchProvenanceEdge e "
            + "WHERE e.researchRun.project.id = :projectId AND e.status = :status")
    List<ResearchProvenanceEdge> findByProjectIdAndStatus(
            @Param("projectId") UUID projectId, @Param("status") ProvenanceRecordStatus status);

    List<ResearchProvenanceEdge> findByResearchRunIdAndStatusOrderByCreatedAtAsc(
            UUID researchRunId, ProvenanceRecordStatus status);

    Optional<ResearchProvenanceEdge> findByResearchRunIdAndIdempotencyKey(UUID researchRunId, String idempotencyKey);

    /** Incoming edges of a node — used to traverse provenance backward from a downstream node. */
    List<ResearchProvenanceEdge> findByResearchRunIdAndToNodeIdAndStatus(
            UUID researchRunId, UUID toNodeId, ProvenanceRecordStatus status);

    /** Outgoing edges of a node — used to detect cycles before adding a new edge. */
    List<ResearchProvenanceEdge> findByResearchRunIdAndFromNodeIdAndStatus(
            UUID researchRunId, UUID fromNodeId, ProvenanceRecordStatus status);
}
