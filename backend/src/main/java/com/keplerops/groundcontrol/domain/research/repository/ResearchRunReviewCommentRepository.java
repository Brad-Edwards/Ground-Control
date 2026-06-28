package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run-scoped review comments for a research run (ADR-067). */
public interface ResearchRunReviewCommentRepository extends JpaRepository<ResearchRunReviewComment, UUID> {

    List<ResearchRunReviewComment> findByResearchRunIdOrderByCreatedAtAsc(UUID researchRunId);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);
}
