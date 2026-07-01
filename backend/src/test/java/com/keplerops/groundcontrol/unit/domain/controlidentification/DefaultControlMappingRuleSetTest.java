package com.keplerops.groundcontrol.unit.domain.controlidentification;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingRule;
import com.keplerops.groundcontrol.domain.controlidentification.service.DefaultControlMappingRuleSet;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The built-in control-mapping rule set (GC-GRC-008) must be valid and cover every threat category so
 * enumerated threats always map to a control objective (or an explicit gap) rather than being dropped.
 */
class DefaultControlMappingRuleSetTest {

    @Test
    void constructsWithUniqueRuleIdsAndVersionProvenance() {
        var ruleSet = DefaultControlMappingRuleSet.standard();
        assertThat(ruleSet.ruleSetId()).isEqualTo(DefaultControlMappingRuleSet.RULE_SET_ID);
        assertThat(ruleSet.version()).isEqualTo(DefaultControlMappingRuleSet.VERSION);
        assertThat(ruleSet.rules()).isNotEmpty();
        var ruleIds = ruleSet.rules().stream().map(ControlMappingRule::ruleId).collect(Collectors.toSet());
        assertThat(ruleIds).hasSameSizeAs(ruleSet.rules());
    }

    @Test
    void coversEveryThreatRuleCategory() {
        var ruleSet = DefaultControlMappingRuleSet.standard();
        var coveredCategories =
                ruleSet.rules().stream().map(ControlMappingRule::category).collect(Collectors.toSet());
        assertThat(coveredCategories).containsAll(Arrays.asList(ThreatRuleCategory.values()));
    }

    @Test
    void strideBaselineCoversEveryStrideCategory() {
        var ruleSet = DefaultControlMappingRuleSet.standard();
        var coveredStride = ruleSet.rules().stream()
                .filter(r -> r.category() == ThreatRuleCategory.STRIDE_BASELINE)
                .map(ControlMappingRule::strideCategory)
                .collect(Collectors.toSet());
        assertThat(coveredStride).containsAll(Arrays.asList(StrideCategory.values()));
    }

    @Test
    void everyRuleCarriesObjectiveGuidanceAndSelectors() {
        var ruleSet = DefaultControlMappingRuleSet.standard();
        for (var rule : ruleSet.rules()) {
            assertThat(rule.objectiveKey()).isNotBlank();
            assertThat(rule.objectiveTitle()).isNotBlank();
            assertThat(rule.defaultGuidance()).isNotBlank();
            assertThat(rule.frameworkSelectors()).isNotEmpty();
        }
    }
}
