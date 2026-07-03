package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunOperationAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GC-RSCH-R005 / ADR-086 — research high-risk operation authorization records for
 * a run. Run-scoped reads only; idempotency is a run-scoped source-action lookup.
 */
public interface ResearchRunOperationAuthorizationRepository
        extends JpaRepository<ResearchRunOperationAuthorization, UUID> {

    List<ResearchRunOperationAuthorization> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    Optional<ResearchRunOperationAuthorization> findByIdAndResearchRunId(UUID id, UUID researchRunId);

    Optional<ResearchRunOperationAuthorization> findByResearchRunIdAndSourceActionId(
            UUID researchRunId, String sourceActionId);
}
