package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Final-manuscript disclosures for a research run (ADR-068 §4). */
public interface ResearchRunDisclosureRepository extends JpaRepository<ResearchRunDisclosure, UUID> {

    Optional<ResearchRunDisclosure> findFirstByResearchRunIdAndStatus(UUID researchRunId, DisclosureStatus status);
}
