package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F008 / ADR-083 — persistence boundary for {@link ProtocolPlan}. */
public interface ProtocolPlanRepository extends JpaRepository<ProtocolPlan, UUID> {

    /** True when a plan already exists for the given artifact attempt. */
    boolean existsByArtifactId(UUID artifactId);

    /** The plan for a specific artifact attempt (unique per artifact_id), scoped to its run. */
    Optional<ProtocolPlan> findByResearchRunIdAndArtifactId(UUID researchRunId, UUID artifactId);

    /** The plan for a specific artifact attempt (unique per artifact_id). */
    Optional<ProtocolPlan> findByArtifactId(UUID artifactId);

    /** The most recently recorded plan for a run, by attempt number. */
    Optional<ProtocolPlan> findFirstByResearchRunIdOrderByAttemptNoDesc(UUID researchRunId);
}
