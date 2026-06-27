package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run-scoped gate-policy/decision rows for a research run (ADR-064 §4). */
public interface ResearchRunGateRepository extends JpaRepository<ResearchRunGate, UUID> {

    List<ResearchRunGate> findByResearchRunIdOrderByGatePointAsc(UUID researchRunId);

    Optional<ResearchRunGate> findByResearchRunIdAndGatePoint(UUID researchRunId, ResearchGatePoint gatePoint);
}
