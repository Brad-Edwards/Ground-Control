package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F006 — persistence boundary for {@link ResearchRunMethodologySource}. */
public interface ResearchRunMethodologySourceRepository extends JpaRepository<ResearchRunMethodologySource, UUID> {

    /** All sources for a given methodology selection. */
    List<ResearchRunMethodologySource> findBySelectionId(UUID selectionId);

    /** Idempotency lookup: find an existing source by its stable reference within a selection. */
    Optional<ResearchRunMethodologySource> findBySelectionIdAndSourceRef(UUID selectionId, String sourceRef);
}
