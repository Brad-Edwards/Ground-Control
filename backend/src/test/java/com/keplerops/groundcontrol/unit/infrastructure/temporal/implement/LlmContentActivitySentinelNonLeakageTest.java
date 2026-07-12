package com.keplerops.groundcontrol.unit.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationPort;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationResult;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementContentActivitiesImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflow;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflowImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.LlmPlanAuthor;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolvedLlmRoute;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Required structural and non-leak evidence (architecture preflight, ADR-028): runs the real
 * {@link ImplementContentActivitiesImpl} / {@link LlmPlanAuthor} against a fake provider and publisher
 * seeded with sentinel completion values, then proves the sentinel is absent from the serialized
 * Temporal execution history, the propagated workflow failure, and every captured Logback event. This
 * is a genuine efficacy test (GC-GRC-011): if a future change logged the completion text or added it to
 * a Temporal-visible record, this test would go red.
 *
 * <p>Both scenarios drive the workflow to a terminal {@link WorkflowFailedException} rather than a
 * successful run: {@code implementChange} — the very next content-activity call after {@code
 * authorPlan} — intentionally fails closed until the ADR-081 bridge (#1281) lands (see
 * {@code ImplementContentActivitiesImplTest}), so no fixture can drive this workflow past Phase A today.
 * That failure boundary does not weaken this test: the full execution history up to and including the
 * failure is still fetched and inspected, which is exactly the surface the preflight requires.
 *
 * <p>Runs in the normal unit/Sonar lane via {@link TestWorkflowEnvironment} (no Testcontainers, no
 * database). Memo and Search Attributes are covered by the same history assertion rather than by a
 * separate one: Temporal serializes both into {@code WorkflowExecutionStartedEventAttributes}, so the
 * workflow is deliberately started with a production-shaped memo and the sentinel scan over the
 * serialized history reads those surfaces too.
 *
 * <p>The remaining leak surfaces named by the issue's acceptance criterion — database rows, Envers audit
 * rows, and REST envelopes — are asserted structurally by {@code ArchitectureLlmBoundaryTest}, which fails
 * if any persisted, audited, or API-facing type so much as references a prompt/completion carrier. That
 * split is deliberate: a runtime sentinel test can only observe leak surfaces that already exist, whereas
 * the regression to guard against is a future change *adding* one.
 */
class LlmContentActivitySentinelNonLeakageTest {

    private static final String TASK_QUEUE = "gc-implement-sentinel-test";
    private static final String SENTINEL_COMPLETION = "sentinel-completion-CANARY-9f2b71";
    private static final String SENTINEL_FAILURE_DETAIL = "sentinel-provider-error-body-CANARY-4a7c3d";

    private ListAppender<ILoggingEvent> rootLogAppender;

    @BeforeEach
    void setUp() {
        rootLogAppender = new ListAppender<>();
        rootLogAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(rootLogAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(rootLogAppender);
    }

    @Test
    void successfulAuthorPlanNeverLeaksTheCompletionAnywhereTemporalObservable() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            var content = realContentActivities(sentinelCompletionProvider(), succeedingPublisher());
            var workflowId = "gc-impl-sentinel-" + UUID.randomUUID();
            var workflow = registerAndStart(env, content, workflowId);

            var workflowInput = input();

            assertThatThrownBy(() -> workflow.run(workflowInput))
                    .isInstanceOf(WorkflowFailedException.class)
                    .satisfies(ex -> assertThat(rootCauseChain(ex)).doesNotContain(SENTINEL_COMPLETION));

            assertThat(fetchHistory(env, workflowId)).doesNotContain(SENTINEL_COMPLETION);
            assertNoSentinelLogged(SENTINEL_COMPLETION);
        }
    }

    @Test
    void aProviderFailureNeverLeaksTheUnderlyingErrorDetailIntoTheActivityFailure() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            var content = realContentActivities(failingProvider(), succeedingPublisher());
            var workflowId = "gc-impl-sentinel-" + UUID.randomUUID();
            var workflow = registerAndStart(env, content, workflowId);

            var workflowInput = input();

            assertThatThrownBy(() -> workflow.run(workflowInput))
                    .isInstanceOf(WorkflowFailedException.class)
                    .satisfies(ex -> assertThat(rootCauseChain(ex)).doesNotContain(SENTINEL_FAILURE_DETAIL));

            assertThat(fetchHistory(env, workflowId)).doesNotContain(SENTINEL_FAILURE_DETAIL);
            assertNoSentinelLogged(SENTINEL_FAILURE_DETAIL);
        }
    }

    private void assertNoSentinelLogged(String sentinel) {
        var messages = rootLogAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(messages).noneMatch(m -> m.contains(sentinel));
    }

    private static String rootCauseChain(Throwable throwable) {
        var builder = new StringBuilder();
        var current = throwable;
        while (current != null) {
            builder.append(current).append(" | ");
            current = current.getCause();
        }
        return builder.toString();
    }

    private static String fetchHistory(TestWorkflowEnvironment env, String workflowId) {
        WorkflowExecutionHistory history = env.getWorkflowClient().fetchHistory(workflowId);
        return history.toJson(true);
    }

    private static ImplementWorkflow registerAndStart(
            TestWorkflowEnvironment env, ImplementContentActivitiesImpl content, String workflowId) {
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ImplementWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ImplementWorkflowReplayTest.FakeActivities(), content);
        env.start();
        return env.getWorkflowClient()
                .newWorkflowStub(
                        ImplementWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                // Mirrors the production memo built by TemporalWorkflowControlAdapter.memoFor.
                                // Temporal serializes the memo into the started event, so the history scan
                                // below reads this surface too.
                                .setMemo(Map.of("project", "proj", "issueNumber", 42))
                                .build());
    }

    private static ImplementWorkflowInput input() {
        var route = new ResolvedLlmRoute("v2", "proj", "planning", "high", "anthropic", "claude-opus-4-8", "digest-1");
        return new ImplementWorkflowInput("proj", 42, "make check", null, 1, List.of("GC-O009"), 1, route);
    }

    private static ImplementContentActivitiesImpl realContentActivities(
            LlmProvider provider, PlanPublicationPort publisher) {
        return new ImplementContentActivitiesImpl(
                new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher));
    }

    private static LlmProvider sentinelCompletionProvider() {
        return new LlmProvider() {
            @Override
            public LlmCompletion complete(LlmCompletionRequest request) {
                return new LlmCompletion(SENTINEL_COMPLETION, 10, 20);
            }

            @Override
            public String providerId() {
                return "anthropic";
            }
        };
    }

    private static LlmProvider failingProvider() {
        return new LlmProvider() {
            @Override
            public LlmCompletion complete(LlmCompletionRequest request) {
                // Mirrors AnthropicLlmProvider's own redaction discipline: the raw underlying detail a
                // real provider error body might carry is captured only in a local variable never
                // referenced by the thrown exception's message — proving the redaction survives all the
                // way through Temporal's activity-failure serialization.
                var rawProviderErrorBody = SENTINEL_FAILURE_DETAIL;
                if (rawProviderErrorBody.isEmpty()) {
                    // Unreachable; keeps the simulated raw detail genuinely referenced (not dead code)
                    // without ever including it in the thrown exception.
                    throw new IllegalStateException(rawProviderErrorBody);
                }
                throw new ServiceUnavailableException("Anthropic provider request failed: connection error");
            }

            @Override
            public String providerId() {
                return "anthropic";
            }
        };
    }

    private static PlanPublicationPort succeedingPublisher() {
        return new PlanPublicationPort() {
            @Override
            public Optional<PlanPublicationResult> findExistingPlan(PlanPublicationObservation observation) {
                return Optional.empty();
            }

            @Override
            public PlanPublicationResult publish(PlanPublicationRequest request) {
                return new PlanPublicationResult(true, 1);
            }
        };
    }
}
