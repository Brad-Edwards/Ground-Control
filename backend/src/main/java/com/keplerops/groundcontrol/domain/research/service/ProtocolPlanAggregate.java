package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import java.util.List;

/**
 * GC-RSCH-F008 / ADR-081 — read view of a protocol plan and its child rows,
 * returned as one bundle so the API layer never re-queries repositories.
 */
public record ProtocolPlanAggregate(
        ProtocolPlan plan, List<ProtocolPlanCoverage> coverages, List<ProtocolPlanSection> sections) {}
