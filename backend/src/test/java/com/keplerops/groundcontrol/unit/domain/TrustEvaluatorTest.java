package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.TrustPolicy;
import com.keplerops.groundcontrol.domain.packregistry.model.TrustPolicyRule;
import com.keplerops.groundcontrol.domain.packregistry.repository.TrustPolicyRepository;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerification;
import com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack;
import com.keplerops.groundcontrol.domain.packregistry.service.TrustEvaluator;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.packregistry.state.TrustOutcome;
import com.keplerops.groundcontrol.domain.packregistry.state.TrustPolicyField;
import com.keplerops.groundcontrol.domain.packregistry.state.TrustPolicyRuleOperator;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrustEvaluatorTest {

    @Mock
    private TrustPolicyRepository trustPolicyRepository;

    private TrustEvaluator evaluator;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final PackIntegrityVerification VERIFIED_INTEGRITY =
            new PackIntegrityVerification("sha256:verified", true, true, true);

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private ResolvedPack makeResolvedPack(String publisher) {
        var project = makeProject();
        var entry = new PackRegistryEntry(project, "test-pack", PackType.CUSTOM, "1.0.0");
        entry.setPublisher(publisher);
        entry.setSourceUrl("https://registry.example.com/test-pack");
        entry.setChecksum("sha256:declared");
        return new ResolvedPack(entry, "1.0.0", "https://registry.example.com/test-pack", "sha256:declared", List.of());
    }

    private TrustPolicyRule rule(
            TrustPolicyField field, TrustPolicyRuleOperator operator, String value, TrustOutcome outcome) {
        return new TrustPolicyRule(field, operator, value, outcome);
    }

    @BeforeEach
    void setUp() {
        evaluator = new TrustEvaluator(trustPolicyRepository);
    }

    @Nested
    class Evaluate {

        @Test
        void returnsUnknownWhenNoPoliciesExist() {
            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of());

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.UNKNOWN);
            assertThat(decision.policyId()).isNull();
        }

        @Test
        void returnsTrustedWhenRuleMatches() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "allow-nist", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(
                    rule(TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "NIST", TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void returnsRejectedWhenRuleRejects() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "block-untrusted", TrustOutcome.TRUSTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "evil-corp", TrustOutcome.REJECTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("evil-corp"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.REJECTED);
        }

        @Test
        void returnsDefaultOutcomeWhenNoRulesMatch() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "strict-policy", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "other-corp", TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.REJECTED);
        }

        @Test
        void firstMatchingRuleWins() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "multi-rule", TrustOutcome.UNKNOWN);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(
                    rule(TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "NIST", TrustOutcome.TRUSTED),
                    rule(TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.CONTAINS, "NIS", TrustOutcome.REJECTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void priorityOrderIsRespected() {
            var project = makeProject();

            var policy1 = new TrustPolicy(project, "reject-first", TrustOutcome.REJECTED);
            setField(policy1, "id", UUID.randomUUID());
            policy1.setRules(List.of(
                    rule(TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "NIST", TrustOutcome.REJECTED)));
            policy1.setPriority(1);

            var policy2 = new TrustPolicy(project, "allow-second", TrustOutcome.TRUSTED);
            setField(policy2, "id", UUID.randomUUID());
            policy2.setRules(List.of(
                    rule(TrustPolicyField.PUBLISHER, TrustPolicyRuleOperator.EQUALS, "NIST", TrustOutcome.TRUSTED)));
            policy2.setPriority(2);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy1, policy2));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.REJECTED);
        }

        @Test
        void containsOperatorWorks() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "contains-test", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.SOURCE_URL,
                    TrustPolicyRuleOperator.CONTAINS,
                    "registry.example.com",
                    TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void matchesPatternOperatorDoesNotExecute() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "pattern-test", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER,
                    TrustPolicyRuleOperator.MATCHES_PATTERN,
                    "NI.*",
                    TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.REJECTED);
        }

        @Test
        void invalidPatternReturnsFalse() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "bad-pattern", TrustOutcome.TRUSTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER,
                    TrustPolicyRuleOperator.MATCHES_PATTERN,
                    "[invalid",
                    TrustOutcome.REJECTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void inListOperatorWorks() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "list-test", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER,
                    TrustPolicyRuleOperator.IN_LIST,
                    "ACME,NIST,ISO",
                    TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void notEqualsOperatorWorks() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "neq-test", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.PUBLISHER,
                    TrustPolicyRuleOperator.NOT_EQUALS,
                    "evil-corp",
                    TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }

        @Test
        void emptyRulesUsesDefaultOutcome() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "no-rules", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of());
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.REJECTED);
        }

        @Test
        void integrityVerificationFieldsCanDrivePolicy() {
            var project = makeProject();
            var policy = new TrustPolicy(project, "signed-only", TrustOutcome.REJECTED);
            setField(policy, "id", UUID.randomUUID());
            policy.setRules(List.of(rule(
                    TrustPolicyField.SIGNER_TRUSTED, TrustPolicyRuleOperator.EQUALS, "true", TrustOutcome.TRUSTED)));
            policy.setPriority(1);

            when(trustPolicyRepository.findByProjectIdAndEnabledOrderByPriorityAsc(PROJECT_ID, true))
                    .thenReturn(List.of(policy));

            var decision = evaluator.evaluate(PROJECT_ID, makeResolvedPack("NIST"), VERIFIED_INTEGRITY);
            assertThat(decision.outcome()).isEqualTo(TrustOutcome.TRUSTED);
        }
    }
}
