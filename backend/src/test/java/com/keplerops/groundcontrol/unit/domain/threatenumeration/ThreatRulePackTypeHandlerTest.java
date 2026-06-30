package com.keplerops.groundcontrol.unit.domain.threatenumeration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredThreatRule;
import com.keplerops.groundcontrol.domain.packregistry.service.EmptyPackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackOperationContext;
import com.keplerops.groundcontrol.domain.packregistry.service.ThreatRuleEntryDefinition;
import com.keplerops.groundcontrol.domain.packregistry.service.ThreatRulePackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.ThreatRulePackTypeHandler;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ThreatRulePackTypeHandler} (GC-GRC-007).
 */
class ThreatRulePackTypeHandlerTest {

    private final ThreatRulePackTypeHandler handler = new ThreatRulePackTypeHandler();

    private ThreatRuleEntryDefinition sampleDefinition(String ruleId) {
        return new ThreatRuleEntryDefinition(
                ruleId,
                "Sample rule: " + ruleId,
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.TAMPERING,
                Set.of(ArchitectureModelElementKind.COMPONENT),
                ThreatRuleMatchPredicate.ALWAYS,
                null,
                "Narrative skeleton",
                "Rationale");
    }

    @Test
    void packTypeIsThreatRulePack() {
        assertThat(handler.packType()).isEqualTo(PackType.THREAT_RULE_PACK);
    }

    @Test
    void applyRegistrationContentStoresRulesOnEntry() {
        var entry = mock(PackRegistryEntry.class);
        var content = new ThreatRulePackRegistrationContent(List.of(sampleDefinition("rule.1")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegisteredThreatRule>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.doNothing().when(entry).setThreatRuleEntries(captor.capture());

        handler.applyRegistrationContent(entry, content);

        var stored = captor.getValue();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().ruleId()).isEqualTo("rule.1");
        assertThat(stored.getFirst().category()).isEqualTo(ThreatRuleCategory.STRIDE_BASELINE);
        assertThat(stored.getFirst().strideCategory()).isEqualTo(StrideCategory.TAMPERING);
        assertThat(stored.getFirst().targetElementKinds()).containsExactly(ArchitectureModelElementKind.COMPONENT);
        assertThat(stored.getFirst().predicate()).isEqualTo(ThreatRuleMatchPredicate.ALWAYS);
    }

    @Test
    void applyRegistrationContentRejectsEmptyRuleList() {
        var entry = mock(PackRegistryEntry.class);
        var content = new ThreatRulePackRegistrationContent(List.of());

        assertThatThrownBy(() -> handler.applyRegistrationContent(entry, content))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("threatRuleEntries");
    }

    @Test
    void applyRegistrationContentRejectsWrongContentType() {
        var entry = mock(PackRegistryEntry.class);
        var wrongContent = EmptyPackRegistrationContent.INSTANCE;

        assertThatThrownBy(() -> handler.applyRegistrationContent(entry, wrongContent))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("THREAT_RULE_PACK");
    }

    @Test
    void applyRegistrationContentRejectsDuplicateRuleIds() {
        var entry = mock(PackRegistryEntry.class);
        var content = new ThreatRulePackRegistrationContent(
                List.of(sampleDefinition("rule.dup"), sampleDefinition("rule.dup")));

        assertThatThrownBy(() -> handler.applyRegistrationContent(entry, content))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate rule id");
    }

    @Test
    void applyRegistrationContentRejectsRuleViolatingInvariants() {
        var entry = mock(PackRegistryEntry.class);
        // A metadata-tag rule with no tag key breaks a rule invariant and must be rejected at the
        // registration write boundary rather than allowed to fail later during read-time enumeration.
        var invalid = new ThreatRuleEntryDefinition(
                "rule.invalid",
                "Invalid rule",
                ThreatRuleCategory.SECRET_HANDLING,
                StrideCategory.INFORMATION_DISCLOSURE,
                Set.of(ArchitectureModelElementKind.DATA_STORE),
                ThreatRuleMatchPredicate.HAS_METADATA_TAG,
                null,
                "Narrative skeleton",
                "Rationale");
        var content = new ThreatRulePackRegistrationContent(List.of(invalid));

        assertThatThrownBy(() -> handler.applyRegistrationContent(entry, content))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Invalid THREAT_RULE_PACK rule definition");
    }

    @Test
    void installThrowsDomainValidationException() {
        var context = mock(PackOperationContext.class);

        assertThatThrownBy(() -> handler.install(context))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("THREAT_RULE_PACK install is not supported");
    }

    @Test
    void upgradeThrowsDomainValidationException() {
        var context = mock(PackOperationContext.class);

        assertThatThrownBy(() -> handler.upgrade(context))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("THREAT_RULE_PACK upgrade is not supported");
    }
}
