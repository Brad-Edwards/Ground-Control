package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F006 — persistence boundary for {@link ResearchRunMethodologySelection}. */
public interface ResearchRunMethodologySelectionRepository
        extends JpaRepository<ResearchRunMethodologySelection, UUID> {

    /** Find the single active (not yet superseded) selection for a run. */
    Optional<ResearchRunMethodologySelection> findFirstByResearchRunIdAndSupersededAtIsNull(UUID researchRunId);

    /** Project-scoped lookup by ID — used to validate cross-run references. */
    Optional<ResearchRunMethodologySelection> findByIdAndResearchRunId(UUID id, UUID researchRunId);
}
