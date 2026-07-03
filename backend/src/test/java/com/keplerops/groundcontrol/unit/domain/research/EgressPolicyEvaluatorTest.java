package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import com.keplerops.groundcontrol.domain.research.service.EgressPolicyEvaluator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GC-RSCH-N006 / ADR-085 §2 — behavioral tests for the default-deny egress
 * evaluator. Each test exercises a real allow/deny decision rather than a
 * tautology.
 */
class EgressPolicyEvaluatorTest {

    @Test
    void localDestinationIsAlwaysPermitted() {
        var decision = EgressPolicyEvaluator.evaluate(
                List.of(), ResearchDataClass.RESTRICTED, ResearchDestinationClass.LOCAL, ResearchDataForm.RAW_CONTENT);
        assertThat(decision.permitted()).isTrue();
        assertThat(decision.basis()).isEqualTo("local");
    }

    @Test
    void noEgressFormIsPermittedRegardlessOfDestination() {
        var decision = EgressPolicyEvaluator.evaluate(
                List.of(), ResearchDataClass.CONFIDENTIAL, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.NONE);
        assertThat(decision.permitted()).isTrue();
        assertThat(decision.basis()).isEqualTo("no_egress");
    }

    @Test
    void emptyPolicyDeniesExternalEgress() {
        var decision = EgressPolicyEvaluator.evaluate(
                List.of(),
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY);
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.basis()).isEqualTo("default_deny");
    }

    @Test
    void matchingAllowancePermitsAtOrBelowAllowedForm() {
        var policy = List.of(new ResearchEgressAllowance(
                ResearchDataClass.PUBLIC,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.SUMMARY,
                "abstracts only"));
        // requested SUMMARY == allowed SUMMARY -> permit
        assertThat(EgressPolicyEvaluator.evaluate(
                                policy,
                                ResearchDataClass.PUBLIC,
                                ResearchDestinationClass.AI_PROVIDER,
                                ResearchDataForm.SUMMARY)
                        .permitted())
                .isTrue();
        // requested DERIVED_METADATA < allowed SUMMARY -> permit
        assertThat(EgressPolicyEvaluator.evaluate(
                                policy,
                                ResearchDataClass.PUBLIC,
                                ResearchDestinationClass.AI_PROVIDER,
                                ResearchDataForm.DERIVED_METADATA)
                        .permitted())
                .isTrue();
    }

    @Test
    void allowanceDoesNotPermitAMoreDisclosingForm() {
        var policy = List.of(new ResearchEgressAllowance(
                ResearchDataClass.CONFIDENTIAL, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY, null));
        var decision = EgressPolicyEvaluator.evaluate(
                policy,
                ResearchDataClass.CONFIDENTIAL,
                ResearchDestinationClass.AI_PROVIDER,
                ResearchDataForm.RAW_CONTENT);
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.basis()).isEqualTo("default_deny");
    }

    @Test
    void allowanceForOneDestinationDoesNotLeakToAnother() {
        var policy = List.of(new ResearchEgressAllowance(
                ResearchDataClass.PUBLIC,
                ResearchDestinationClass.CITATION_PROVIDER,
                ResearchDataForm.RAW_CONTENT,
                null));
        var decision = EgressPolicyEvaluator.evaluate(
                policy, ResearchDataClass.PUBLIC, ResearchDestinationClass.AI_PROVIDER, ResearchDataForm.SUMMARY);
        assertThat(decision.permitted()).isFalse();
    }
}
