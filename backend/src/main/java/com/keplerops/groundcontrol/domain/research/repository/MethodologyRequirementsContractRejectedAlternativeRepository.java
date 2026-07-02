package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractRejectedAlternative;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GC-RSCH-N012 / ADR-080 — persistence boundary for {@link
 * MethodologyRequirementsContractRejectedAlternative}.
 */
public interface MethodologyRequirementsContractRejectedAlternativeRepository
        extends JpaRepository<MethodologyRequirementsContractRejectedAlternative, UUID> {

    /** All rejected alternatives for a contract, in insertion order. */
    List<MethodologyRequirementsContractRejectedAlternative> findByContractIdOrderByCreatedAtAsc(UUID contractId);
}
