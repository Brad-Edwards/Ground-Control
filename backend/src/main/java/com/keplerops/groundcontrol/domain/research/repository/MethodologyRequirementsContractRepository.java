package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F007 / ADR-080 — persistence boundary for {@link MethodologyRequirementsContract}. */
public interface MethodologyRequirementsContractRepository
        extends JpaRepository<MethodologyRequirementsContract, UUID> {

    /** The contract for a specific artifact attempt (unique per artifact_id). */
    Optional<MethodologyRequirementsContract> findByArtifactId(UUID artifactId);

    /** True when a contract already exists for the given artifact attempt. */
    boolean existsByArtifactId(UUID artifactId);
}
