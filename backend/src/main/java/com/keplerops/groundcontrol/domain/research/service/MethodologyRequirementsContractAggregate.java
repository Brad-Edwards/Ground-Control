package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractRejectedAlternative;
import java.util.List;

/**
 * GC-RSCH-F007 / ADR-079 — read view of a methodology requirements contract and
 * its child rows, returned as one bundle so the API layer never re-queries
 * repositories. {@code sourceLinks} carries every link for the contract; the
 * response groups them by entry id.
 */
public record MethodologyRequirementsContractAggregate(
        MethodologyRequirementsContract contract,
        List<MethodologyRequirementsContractEntry> entries,
        List<MethodologyRequirementsContractEntrySourceLink> sourceLinks,
        List<MethodologyRequirementsContractRejectedAlternative> rejectedAlternatives) {}
