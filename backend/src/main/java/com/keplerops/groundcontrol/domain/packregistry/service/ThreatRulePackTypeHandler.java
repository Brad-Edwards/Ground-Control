package com.keplerops.groundcontrol.domain.packregistry.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredThreatRule;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pack type handler for {@code THREAT_RULE_PACK} entries (GC-GRC-007). Validates and stores
 * threat rule definitions at registration time. Install and upgrade are intentionally unsupported:
 * rule packs have no downstream installed aggregate — enumeration reads the registered entry
 * directly, so the install lifecycle that {@code CONTROL_PACK} uses is not applicable.
 */
@Service
public class ThreatRulePackTypeHandler implements PackTypeHandler {

    @Override
    public PackType packType() {
        return PackType.THREAT_RULE_PACK;
    }

    @Override
    public void applyRegistrationContent(PackRegistryEntry entry, PackRegistrationContent content) {
        if (!(content instanceof ThreatRulePackRegistrationContent threatContent)) {
            throw new DomainValidationException(
                    "THREAT_RULE_PACK registry entries must include threatRuleEntries content");
        }
        if (threatContent.entries().isEmpty()) {
            throw new DomainValidationException("THREAT_RULE_PACK registry entries must include threatRuleEntries");
        }
        entry.setThreatRuleEntries(toRegisteredThreatRules(threatContent.entries()));
    }

    @Override
    public PackOperationResult install(PackOperationContext context) {
        throw new DomainValidationException(
                "THREAT_RULE_PACK install is not supported; rule packs are consumed directly by threat enumeration");
    }

    @Override
    public PackOperationResult upgrade(PackOperationContext context) {
        throw new DomainValidationException(
                "THREAT_RULE_PACK upgrade is not supported; rule packs are consumed directly by threat enumeration");
    }

    /**
     * Validate and persist threat rules at the registration write boundary. Each definition is run
     * through the {@link ThreatRule} constructor so its invariants (blank rule id, null
     * category/predicate/STRIDE, empty target kinds, predicate/metadataTagKey coupling) are caught
     * here and surface as a {@link DomainValidationException} — never deferred to read-time
     * enumeration. Duplicate rule ids are rejected so registered packs cannot produce colliding
     * candidate identities.
     */
    private List<RegisteredThreatRule> toRegisteredThreatRules(List<ThreatRuleEntryDefinition> entries) {
        Set<String> seenRuleIds = new HashSet<>();
        List<RegisteredThreatRule> registered = new ArrayList<>(entries.size());
        for (var entry : entries) {
            ThreatRule rule;
            try {
                rule = new ThreatRule(
                        entry.ruleId(),
                        entry.title(),
                        entry.category(),
                        entry.strideCategory(),
                        entry.targetElementKinds(),
                        entry.predicate(),
                        entry.metadataTagKey(),
                        entry.narrativeSkeleton(),
                        entry.rationale());
            } catch (IllegalArgumentException e) {
                throw new DomainValidationException("Invalid THREAT_RULE_PACK rule definition: " + e.getMessage());
            }
            if (!seenRuleIds.add(rule.ruleId())) {
                throw new DomainValidationException("THREAT_RULE_PACK contains duplicate rule id: " + rule.ruleId());
            }
            registered.add(new RegisteredThreatRule(
                    rule.ruleId(),
                    rule.title(),
                    rule.category(),
                    rule.strideCategory(),
                    rule.targetElementKinds(),
                    rule.predicate(),
                    rule.metadataTagKey(),
                    rule.narrativeSkeleton(),
                    rule.rationale()));
        }
        return registered;
    }
}
