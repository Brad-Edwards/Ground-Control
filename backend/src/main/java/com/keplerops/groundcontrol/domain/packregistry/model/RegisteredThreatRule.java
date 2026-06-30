package com.keplerops.groundcontrol.domain.packregistry.model;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Set;

/**
 * Persisted representation of a single threat rule within a THREAT_RULE_PACK registry entry.
 * Jackson serialises enum fields by name, giving a stable JSON form. Stored as part of the
 * {@code threat_rule_entries} TEXT column on {@code pack_registry_entry}.
 */
public record RegisteredThreatRule(
        String ruleId,
        String title,
        ThreatRuleCategory category,
        StrideCategory strideCategory,
        Set<ArchitectureModelElementKind> targetElementKinds,
        ThreatRuleMatchPredicate predicate,
        String metadataTagKey,
        String narrativeSkeleton,
        String rationale) {}
