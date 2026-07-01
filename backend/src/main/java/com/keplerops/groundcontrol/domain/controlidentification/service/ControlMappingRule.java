package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Set;

/**
 * Immutable in-memory representation of a single control-mapping rule within a
 * {@link ControlMappingRuleSet} (GC-GRC-008). A rule maps a threat category (optionally narrowed to a
 * single STRIDE category) to a control objective, and selects candidate controls by matching its
 * {@code frameworkSelectors} — recognized-framework family or control identifiers such as {@code AC},
 * {@code IA-2}, {@code SC-8} — against the framework identifiers carried by available controls.
 *
 * <p>The compact constructor validates all invariants so callers receive a fully valid rule or a clear
 * {@link IllegalArgumentException}.
 */
public record ControlMappingRule(
        String ruleId,
        ThreatRuleCategory category,
        /** When non-null the rule fires only for threats of this STRIDE category; when null it fires for
         * every threat in {@code category} regardless of STRIDE. */
        StrideCategory strideCategory,
        String objectiveKey,
        String objectiveTitle,
        Set<String> frameworkSelectors,
        String defaultGuidance,
        String rationale) {

    public ControlMappingRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ControlMappingRule ruleId must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("ControlMappingRule category must not be null");
        }
        if (objectiveKey == null || objectiveKey.isBlank()) {
            throw new IllegalArgumentException("ControlMappingRule objectiveKey must not be blank");
        }
        if (frameworkSelectors == null || frameworkSelectors.isEmpty()) {
            throw new IllegalArgumentException("ControlMappingRule frameworkSelectors must not be empty");
        }
        for (var selector : frameworkSelectors) {
            if (selector == null || selector.isBlank()) {
                throw new IllegalArgumentException("ControlMappingRule frameworkSelectors must not contain blanks");
            }
        }
        frameworkSelectors = Set.copyOf(frameworkSelectors);
    }
}
