package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provenance edges for a research run (ADR-069 §2). All queries are run-scoped. */
public interface ResearchProvenanceEdgeRepository extends JpaRepository<ResearchProvenanceEdge, UUID> {

    List<ResearchProvenanceEdge> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

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
