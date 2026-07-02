package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F007 / ADR-080 — persistence boundary for {@link MethodologyRequirementsContractEntry}. */
public interface MethodologyRequirementsContractEntryRepository
        extends JpaRepository<MethodologyRequirementsContractEntry, UUID> {

    /** All entries for a contract, in insertion order. */
    List<MethodologyRequirementsContractEntry> findByContractIdOrderByCreatedAtAsc(UUID contractId);
}
