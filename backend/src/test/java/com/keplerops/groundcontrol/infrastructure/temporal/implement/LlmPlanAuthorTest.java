package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationPort;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationResult;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolvedLlmRoute;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code LlmPlanAuthor} is the LLM-backed collaborator composed by {@code ImplementContentActivitiesImpl}
 * for {@code authorPlan} only (architecture preflight: the provider dependency must not reach every
 * content-activity method). It builds the prompt inside the activity process, invokes the port for the
 * resolved route's provider, keeps the completion in process, and hands it to {@link PlanPublicationPort}
 * — returning only the bounded {@code (posted, commentId)} fact.
 */
class LlmPlanAuthorTest {

    private static final ResolvedLlmRoute ROUTE = new ResolvedLlmRoute(
            "v2", "ground-control", "planning", "high", "anthropic", "claude-opus-4-8", "digest-1");

    @Test
    void authorPlanInvokesTheRoutedProviderAndPublishesTheCompletion() {
        var completion = new LlmCompletion("plan body", 10, 20);
        var provider = new CapturingProvider("anthropic", completion);
        var publisher = new CapturingPublisher(new PlanPublicationResult(true, 42));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput("ground-control", ROUTE, 1280, List.of("GC-O009"), "issue-1280:plan");

        var result = author.authorPlan(input);

        assertThat(result.posted()).isTrue();
        assertThat(result.commentId()).isEqualTo(42);
        assertThat(provider.lastRequest).isNotNull();
        assertThat(provider.lastRequest.modelId()).isEqualTo("claude-opus-4-8");
        assertThat(publisher.lastRequest).isNotNull();
        assertThat(publisher.lastRequest.project()).isEqualTo("ground-control");
        assertThat(publisher.lastRequest.issueNumber()).isEqualTo(1280);
        assertThat(publisher.lastRequest.requirementUids()).containsExactly("GC-O009");
        assertThat(publisher.lastRequest.idempotencyKey()).isEqualTo("issue-1280:plan");
        assertThat(publisher.lastRequest.completion()).isSameAs(completion);
    }

    @Test
    void authorPlanBuildsAPromptReferencingTheIssueAndRequirements() {
        var provider = new CapturingProvider("anthropic", new LlmCompletion("plan body", 1, 1));
        var publisher = new CapturingPublisher(new PlanPublicationResult(true, 1));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput("ground-control", ROUTE, 1280, List.of("GC-O009"), "issue-1280:plan");

        author.authorPlan(input);

        assertThat(provider.lastRequest.prompt()).contains("1280").contains("GC-O009");
    }

    @Test
    void authorPlanFailsClosedForAnUnregisteredProvider() {
        var publisher = new CapturingPublisher(new PlanPublicationResult(true, 1));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of()), publisher);
        var input = new AuthorPlanInput("ground-control", ROUTE, 1280, List.of(), "issue-1280:plan");

        assertThatThrownBy(() -> author.authorPlan(input)).isInstanceOf(DomainValidationException.class);
    }

    /** Both halves of the {@code project == null || project.isBlank()} guard, not just the blank one. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void authorPlanRejectsAMissingProject(String project) {
        var provider = new CapturingProvider("anthropic", new LlmCompletion("plan body", 1, 1));
        var publisher = new CapturingPublisher(new PlanPublicationResult(true, 1));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput(project, ROUTE, 1280, List.of(), "issue-1280:plan");

        assertThatThrownBy(() -> author.authorPlan(input)).isInstanceOf(DomainValidationException.class);
        assertThat(provider.invocations).isZero();
    }

    @Test
    void authorPlanRejectsAMissingRoute() {
        var provider = new CapturingProvider("anthropic", new LlmCompletion("plan body", 1, 1));
        var publisher = new CapturingPublisher(new PlanPublicationResult(true, 1));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput("ground-control", null, 1280, List.of(), "issue-1280:plan");

        assertThatThrownBy(() -> author.authorPlan(input)).isInstanceOf(DomainValidationException.class);
    }

    /**
     * Observe-before-infer (codex review, cycle 1). Temporal activities are at-least-once, so an activity
     * that infers first and publishes second buys a second completion on every retry — including the retry
     * that follows a publication whose acknowledgement was merely lost. The plan must already exist check
     * has to run BEFORE the provider is touched, and this test goes red if that ordering is ever inverted.
     */
    @Test
    void authorPlanNeverInvokesTheProviderWhenAPlanWasAlreadyPublished() {
        var provider = new CapturingProvider("anthropic", new LlmCompletion("plan body", 1, 1));
        var publisher = new CapturingPublisher(null, new PlanPublicationResult(true, 99), null);
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput("ground-control", ROUTE, 1280, List.of("GC-O009"), "issue-1280:plan");

        var result = author.authorPlan(input);

        assertThat(result.posted()).isTrue();
        assertThat(result.commentId()).isEqualTo(99);
        assertThat(provider.invocations).isZero();
        assertThat(publisher.lastObservation.idempotencyKey()).isEqualTo("issue-1280:plan");
        assertThat(publisher.lastRequest).isNull();
    }

    /** A failing publication must not multiply the billable inference within the attempt. */
    @Test
    void authorPlanInvokesTheProviderExactlyOnceWhenPublicationFails() {
        var provider = new CapturingProvider("anthropic", new LlmCompletion("plan body", 1, 1));
        var publisher = new CapturingPublisher(null, null, new IllegalStateException("publication failed"));
        var author = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        var input = new AuthorPlanInput("ground-control", ROUTE, 1280, List.of("GC-O009"), "issue-1280:plan");

        assertThatThrownBy(() -> author.authorPlan(input)).isInstanceOf(IllegalStateException.class);

        assertThat(provider.invocations).isEqualTo(1);
    }

    private static final class CapturingProvider implements LlmProvider {
        private final String providerId;
        private final LlmCompletion completion;
        private volatile LlmCompletionRequest lastRequest;
        private volatile int invocations;

        CapturingProvider(String providerId, LlmCompletion completion) {
            this.providerId = providerId;
            this.completion = completion;
        }

        @Override
        public LlmCompletion complete(LlmCompletionRequest request) {
            this.lastRequest = request;
            this.invocations++;
            return completion;
        }

        @Override
        public String providerId() {
            return providerId;
        }
    }

    private static final class CapturingPublisher implements PlanPublicationPort {
        private final PlanPublicationResult result;
        private final PlanPublicationResult existing;
        private final RuntimeException publishFailure;
        private volatile PlanPublicationRequest lastRequest;
        private volatile PlanPublicationObservation lastObservation;

        CapturingPublisher(PlanPublicationResult result) {
            this(result, null, null);
        }

        CapturingPublisher(
                PlanPublicationResult result, PlanPublicationResult existing, RuntimeException publishFailure) {
            this.result = result;
            this.existing = existing;
            this.publishFailure = publishFailure;
        }

        @Override
        public Optional<PlanPublicationResult> findExistingPlan(PlanPublicationObservation observation) {
            this.lastObservation = observation;
            return Optional.ofNullable(existing);
        }

        @Override
        public PlanPublicationResult publish(PlanPublicationRequest request) {
            this.lastRequest = request;
            if (publishFailure != null) {
                throw publishFailure;
            }
            return result;
        }
    }
}
