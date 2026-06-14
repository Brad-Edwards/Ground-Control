package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import java.util.List;

/**
 * A requirement paired with its traceability links, used to assemble the
 * traceability matrix (GC-Q003) in a single aggregate read rather than an
 * N+1 fetch per requirement.
 */
public record RequirementWithLinks(Requirement requirement, List<TraceabilityLink> links) {}
