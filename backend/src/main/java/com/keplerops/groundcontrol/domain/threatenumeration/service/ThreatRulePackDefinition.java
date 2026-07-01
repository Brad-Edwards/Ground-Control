package com.keplerops.groundcontrol.domain.threatenumeration.service;

import java.util.List;

/**
 * Canonical in-memory representation of a resolved and integrity-verified THREAT_RULE_PACK
 * registry entry. Analogous to {@code DataClassificationLatticeDefinition}. Produced by
 * {@link ThreatEnumerationService#resolvePackDefinition} and consumed by the pure
 * {@link ThreatEnumerationService#enumerate} method.
 */
public record ThreatRulePackDefinition(String packId, String resolvedVersion, String checksum, List<ThreatRule> rules) {

    public ThreatRulePackDefinition {
        rules = List.copyOf(rules);
    }
}
