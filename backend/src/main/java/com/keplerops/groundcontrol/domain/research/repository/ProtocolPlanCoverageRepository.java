package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F008 / ADR-081 — persistence boundary for {@link ProtocolPlanCoverage}. */
public interface ProtocolPlanCoverageRepository extends JpaRepository<ProtocolPlanCoverage, UUID> {

    /** All coverage rows for a plan, in insertion order. */
    List<ProtocolPlanCoverage> findByProtocolPlanId(UUID protocolPlanId);
}
