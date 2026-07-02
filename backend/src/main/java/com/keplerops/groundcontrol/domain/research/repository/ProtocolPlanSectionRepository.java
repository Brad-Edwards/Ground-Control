package com.keplerops.groundcontrol.domain.research.repository;

import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** GC-RSCH-F009 / ADR-081 — persistence boundary for {@link ProtocolPlanSection}. */
public interface ProtocolPlanSectionRepository extends JpaRepository<ProtocolPlanSection, UUID> {

    /** All sections for a plan, in insertion order. */
    List<ProtocolPlanSection> findByProtocolPlanId(UUID protocolPlanId);
}
