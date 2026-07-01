package com.keplerops.groundcontrol.domain.threatenumeration.service;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Map;

/**
 * An intermediate enumeration candidate: a deterministically derived, per-element STRIDE threat
 * produced by the rule engine. Candidates are NOT curated ThreatModel records; they require a
 * downstream curation step (GC-GRC-007 clause e). {@code matchedFacts} carries bounded
 * references to the persisted architecture-model state that triggered the match — never raw
 * adapter payloads.
 */
public record ThreatCandidate(
        String producingRuleId,
        ThreatRuleCategory category,
        StrideCategory strideCategory,
        String elementStableKey,
        ArchitectureModelElementKind elementKind,
        Map<String, String> matchedFacts,
        String narrative) {

    public ThreatCandidate {
        matchedFacts = matchedFacts == null ? Map.of() : Map.copyOf(matchedFacts);
    }
}
