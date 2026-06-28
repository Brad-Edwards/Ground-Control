package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provenance nodes for a research run (ADR-069 §2). All queries are run-scoped. */
public interface ResearchProvenanceNodeRepository extends JpaRepository<ResearchProvenanceNode, UUID> {

    List<ResearchProvenanceNode> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    List<ResearchProvenanceNode> findByResearchRunIdAndStatusOrderByCreatedAtAsc(
            UUID researchRunId, ProvenanceRecordStatus status);

    Optional<ResearchProvenanceNode> findByResearchRunIdAndIdempotencyKey(UUID researchRunId, String idempotencyKey);

    Optional<ResearchProvenanceNode> findByIdAndResearchRunId(UUID id, UUID researchRunId);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);
}
