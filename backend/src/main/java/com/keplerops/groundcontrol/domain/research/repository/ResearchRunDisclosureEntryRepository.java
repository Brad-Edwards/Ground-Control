package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Disclosure entries belonging to a research-run disclosure (ADR-068 §4). */
public interface ResearchRunDisclosureEntryRepository extends JpaRepository<ResearchRunDisclosureEntry, UUID> {

    List<ResearchRunDisclosureEntry> findByDisclosureId(UUID disclosureId);
}
