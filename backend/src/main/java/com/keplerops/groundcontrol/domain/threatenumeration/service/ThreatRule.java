package com.keplerops.groundcontrol.domain.threatenumeration.service;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Set;

/**
 * Immutable in-memory representation of a single threat enumeration rule within a
 * {@link ThreatRulePackDefinition}. Compact constructor validates all invariants so callers
 * receive a fully valid rule or a clear {@link IllegalArgumentException}.
 */
public record ThreatRule(
        String ruleId,
        String title,
        ThreatRuleCategory category,
        StrideCategory strideCategory,
        Set<ArchitectureModelElementKind> targetElementKinds,
        ThreatRuleMatchPredicate predicate,
        String metadataTagKey,
        String narrativeSkeleton,
        String rationale) {

    public ThreatRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ThreatRule ruleId must not be blank");
        }
        if (strideCategory == null) {
            throw new IllegalArgumentException("ThreatRule strideCategory must not be null");
        }
        if (category == null) {
            throw new IllegalArgumentException("ThreatRule category must not be null");
        }
        if (predicate == null) {
            throw new IllegalArgumentException("ThreatRule predicate must not be null");
        }
        if (targetElementKinds == null || targetElementKinds.isEmpty()) {
            throw new IllegalArgumentException("ThreatRule targetElementKinds must not be empty");
        }
        if (predicate == ThreatRuleMatchPredicate.HAS_METADATA_TAG
                && (metadataTagKey == null || metadataTagKey.isBlank())) {
            throw new IllegalArgumentException(
                    "ThreatRule with predicate HAS_METADATA_TAG must have a non-blank metadataTagKey");
        }
        if (predicate != ThreatRuleMatchPredicate.HAS_METADATA_TAG
                && metadataTagKey != null
                && !metadataTagKey.isBlank()) {
            throw new IllegalArgumentException(
                    "ThreatRule metadataTagKey must only be set when predicate is HAS_METADATA_TAG");
        }
        targetElementKinds = Set.copyOf(targetElementKinds);
    }
}
