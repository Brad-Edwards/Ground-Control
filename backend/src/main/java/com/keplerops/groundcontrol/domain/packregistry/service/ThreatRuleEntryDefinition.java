package com.keplerops.groundcontrol.domain.packregistry.service;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Set;

/**
 * Input record carrying the definition of a single threat rule as provided at registration time.
 * Stored into {@code PackRegistryEntry.threatRuleEntries} via {@link ThreatRulePackTypeHandler}.
 */
public record ThreatRuleEntryDefinition(
        String ruleId,
        String title,
        ThreatRuleCategory category,
        StrideCategory strideCategory,
        Set<ArchitectureModelElementKind> targetElementKinds,
        ThreatRuleMatchPredicate predicate,
        String metadataTagKey,
        String narrativeSkeleton,
        String rationale) {}
