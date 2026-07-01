package com.keplerops.groundcontrol.domain.controlidentification.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A versioned, immutable set of {@link ControlMappingRule}s (GC-GRC-008). {@code ruleSetId} and
 * {@code version} are the rule provenance carried onto every produced {@code ControlCandidate}, so a
 * candidate can always be traced back to the exact rule set that selected it. Duplicate rule ids are
 * rejected so candidate provenance is unambiguous.
 */
public record ControlMappingRuleSet(String ruleSetId, String version, List<ControlMappingRule> rules) {

    public ControlMappingRuleSet {
        if (ruleSetId == null || ruleSetId.isBlank()) {
            throw new IllegalArgumentException("ControlMappingRuleSet ruleSetId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ControlMappingRuleSet version must not be blank");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("ControlMappingRuleSet rules must not be empty");
        }
        Set<String> seen = new HashSet<>();
        for (var rule : rules) {
            if (rule == null) {
                throw new IllegalArgumentException("ControlMappingRuleSet rules must not contain nulls");
            }
            if (!seen.add(rule.ruleId())) {
                throw new IllegalArgumentException(
                        "ControlMappingRuleSet contains duplicate rule id: " + rule.ruleId());
            }
        }
        rules = List.copyOf(rules);
    }
}
