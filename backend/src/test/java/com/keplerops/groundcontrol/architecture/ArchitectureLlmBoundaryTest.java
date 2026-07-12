package com.keplerops.groundcontrol.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.util.Set;
import org.hibernate.envers.Audited;

/**
 * ADR-082 rule-ownership row "ArchUnit: deterministic activities LLM-free | ADR-028 LLM boundary |
 * #1280". Proves the deterministic {@code /implement} activity seam ({@code ImplementActivities} /
 * {@code ImplementActivitiesImpl}) is structurally unable to reach the LLM provider port, registry,
 * or adapters, and that no class outside the approved adapter package calls the Anthropic provider
 * directly.
 *
 * <p>The trailing rules are the structural half of the ADR-028 redaction discipline and of the issue's
 * "history, log, and audit inspection" acceptance criterion. The sensitive LLM carriers hold prompt and
 * completion text, so no persisted entity, no Envers-audited type, and no API-facing class may reference
 * them at all. These go red the moment someone adds a prompt/completion field to a database row, an audit
 * row, or a REST envelope — a regression the runtime sentinel test cannot observe, because a leak surface
 * that does not exist yet cannot be asserted against dynamically.
 *
 * <p>Kept in a dedicated file (rather than added to {@link ArchitectureTest}) so the LLM-boundary rule set
 * reads as one cohesive, ADR-082-traceable unit.
 */
@AnalyzeClasses(packages = "com.keplerops.groundcontrol", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureLlmBoundaryTest {

    /**
     * Prompt/completion-bearing carriers. {@code ResolvedLlmRoute} is deliberately absent: it is the closed
     * safe scalar set (provider/model/tier/digest) and is the one LLM shape allowed to cross durable
     * boundaries.
     */
    private static final Set<String> SENSITIVE_LLM_CARRIERS =
            Set.of("LlmCompletionRequest", "LlmCompletion", "PlanPublicationRequest");

    private static final DescribedPredicate<JavaClass> A_PROMPT_OR_COMPLETION_CARRIER =
            new DescribedPredicate<>("a prompt- or completion-bearing LLM carrier") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return SENSITIVE_LLM_CARRIERS.contains(javaClass.getSimpleName());
                }
            };

    @ArchTest
    static final ArchRule deterministic_implement_activities_must_not_depend_on_the_llm_boundary = noClasses()
            .that()
            .haveSimpleName("ImplementActivities")
            .or()
            .haveSimpleName("ImplementActivitiesImpl")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..domain.llm..", "..infrastructure.llm..");

    @ArchTest
    static final ArchRule only_the_anthropic_adapter_package_may_call_the_anthropic_provider_directly = noClasses()
            .that()
            .resideOutsideOfPackage("..infrastructure.llm.anthropic..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("AnthropicLlmProvider");

    @ArchTest
    static final ArchRule no_persisted_entity_may_reference_a_prompt_or_completion_carrier = noClasses()
            .that()
            .areAnnotatedWith(Entity.class)
            .should()
            .dependOnClassesThat(A_PROMPT_OR_COMPLETION_CARRIER)
            .because("prompts and completions must never reach a database row (ADR-028 redaction discipline)");

    @ArchTest
    static final ArchRule no_audited_type_may_reference_a_prompt_or_completion_carrier = noClasses()
            .that()
            .areAnnotatedWith(Audited.class)
            .should()
            .dependOnClassesThat(A_PROMPT_OR_COMPLETION_CARRIER)
            .because("prompts and completions must never reach an Envers audit row (ADR-028 redaction discipline)");

    @ArchTest
    static final ArchRule no_api_class_may_reference_a_prompt_or_completion_carrier = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat(A_PROMPT_OR_COMPLETION_CARRIER)
            .because("prompts and completions must never reach a REST request or response envelope "
                    + "(ADR-028 redaction discipline)");
}
