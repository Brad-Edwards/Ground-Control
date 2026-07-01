package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GC-RSCH-F007 / ADR-079 — persistence boundary for {@link
 * MethodologyRequirementsContractEntrySourceLink}.
 */
public interface MethodologyRequirementsContractEntrySourceLinkRepository
        extends JpaRepository<MethodologyRequirementsContractEntrySourceLink, UUID> {

    /** All source links for the entries of a contract (join through the entry). */
    List<MethodologyRequirementsContractEntrySourceLink> findByEntryContractIdOrderByCreatedAtAsc(UUID contractId);
}
