package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only gate decision-log rows for a research run (ADR-066). */
public interface ResearchRunGateDecisionLogRepository extends JpaRepository<ResearchRunGateDecisionLog, UUID> {

    List<ResearchRunGateDecisionLog> findByResearchRunIdOrderByDecidedAtAsc(UUID researchRunId);

    List<ResearchRunGateDecisionLog> findByResearchRunIdAndGatePoint(UUID researchRunId, ResearchGatePoint gatePoint);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);

    boolean existsByResearchRunIdAndDecisionOutcome(UUID researchRunId, ResearchGateDecisionOutcome decisionOutcome);
}
