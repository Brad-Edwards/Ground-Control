package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Artifact manifest rows for a research run (ADR-064 §6). */
public interface ResearchRunArtifactRepository extends JpaRepository<ResearchRunArtifact, UUID> {

    List<ResearchRunArtifact> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    Optional<ResearchRunArtifact> findByResearchRunIdAndIdempotencyKey(UUID researchRunId, String idempotencyKey);

    Optional<ResearchRunArtifact> findByResearchRunIdAndArtifactTypeAndStatus(
            UUID researchRunId, ResearchArtifactType artifactType, ResearchArtifactStatus status);

    int countByResearchRunIdAndArtifactTypeAndStatus(
            UUID researchRunId, ResearchArtifactType artifactType, ResearchArtifactStatus status);
}
