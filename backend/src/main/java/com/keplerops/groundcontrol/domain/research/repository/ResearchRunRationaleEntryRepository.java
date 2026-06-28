package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only rationale-ledger entries for a research run (ADR-068). */
public interface ResearchRunRationaleEntryRepository extends JpaRepository<ResearchRunRationaleEntry, UUID> {

    List<ResearchRunRationaleEntry> findByResearchRunIdOrderByRecordedAtAsc(UUID researchRunId);

    boolean existsByIdAndResearchRunId(UUID id, UUID researchRunId);
}
