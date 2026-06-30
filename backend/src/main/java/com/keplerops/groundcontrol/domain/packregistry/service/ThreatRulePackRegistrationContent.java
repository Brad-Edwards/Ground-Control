package com.keplerops.groundcontrol.domain.packregistry.service;

import java.util.List;

/**
 * Registration content for a {@code THREAT_RULE_PACK} registry entry. Carries the list of
 * {@link ThreatRuleEntryDefinition}s that the handler validates and stores.
 */
public record ThreatRulePackRegistrationContent(List<ThreatRuleEntryDefinition> entries)
        implements PackRegistrationContent {

    public ThreatRulePackRegistrationContent {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
