package com.keplerops.groundcontrol.unit.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.infrastructure.llm.BridgePendingPlanPublisher;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementContentActivitiesImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflow;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflowImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.LlmPlanAuthor;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Upgrade evidence for the #1280 LLM content seam (codex review, cycle 2).
 *
 * <p>{@code implement-workflow.v1} declares {@code route} optional, so an execution started by the previous
 * worker legitimately carries none. This change made the route operationally required at {@code authorPlan}
 * and moved three content activities onto different {@code ActivityOptions}. Both tests below cover what a
 * worker rolling out this change actually does to such an execution.
 *
 * <p><strong>On what the replay test does and does not prove.</strong> Temporal's replay determinism check
 * compares the command sequence, NOT an activity's recorded scheduling attributes or input payload. It
 * therefore cannot, on its own, detect the options/route change — a pre-change history replays clean with or
 * without the {@code llm-content-seam} version guard, and a test asserting otherwise would be false comfort.
 * The replay case is kept because it does pin a real property (the pre-change command sequence still
 * replays, so the change did not reorder or unconditionally insert activity calls), and the fail-closed case
 * below is what pins the behavior that actually regressed.
 */
class ImplementWorkflowUpgradeReplayTest {

    private static final String PRE_CHANGE_HISTORY = "fixtures/temporal/implement-workflow-pre-1280-history.json";
    private static final String TASK_QUEUE = "gc-implement-upgrade-test";

    /**
     * A history recorded against the base branch before this change (commit 3f8226de) — its {@code AuthorPlan}
     * was scheduled through the single long-running content stub with a three-field input carrying no project
     * and no route — still replays against the current workflow. Goes red if the change reorders the phase
     * graph or unconditionally inserts a new activity call into the recorded command sequence.
     */
    @Test
    void aHistoryRecordedBeforeTheLlmContentSeamStillReplaysDeterministically() {
        WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(readFixture(PRE_CHANGE_HISTORY));

        assertThatCode(() -> WorkflowReplayer.replayWorkflowExecution(history, ImplementWorkflowImpl.class))
                .doesNotThrowAnyException();
    }

    /**
     * The route is what binds an execution to a trusted, project-scoped provider and model. A run that reaches
     * LLM plan authoring without one must fail closed on a controlled, non-retryable validation error — never
     * proceed to a provider with an unbound route, and never surface an unhandled crash. Goes red if the null
     * check in {@code LlmPlanAuthor} is removed or downgraded.
     */
    @Test
    void anExecutionThatReachesPlanAuthoringWithoutARouteFailsClosed() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            Worker worker = env.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(ImplementWorkflowImpl.class);
            // The real content seam, so the null route is rejected by production code, not by a fixture.
            var contentSeam = new ImplementContentActivitiesImpl(
                    new LlmPlanAuthor(new LlmProviderRegistry(List.of()), new BridgePendingPlanPublisher()));
            worker.registerActivitiesImplementations(new ImplementWorkflowReplayTest.FakeActivities(), contentSeam);
            env.start();

            ImplementWorkflow workflow = env.getWorkflowClient()
                    .newWorkflowStub(
                            ImplementWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setTaskQueue(TASK_QUEUE)
                                    .setWorkflowId("gc-impl-noroute-" + UUID.randomUUID())
                                    .build());

            // implement-workflow.v1 permits a null route; this is that input.
            var routeless = new ImplementWorkflowInput("proj", 42, "make check", "sonar-key", 1, List.of(), 1, null);

            // Assert on the CAUSE, not merely that the run failed. The registry here is empty, so a run that
            // stopped rejecting the null route would still fail — just later, on provider lookup. Only naming
            // the route in the failure distinguishes "fails closed on the unbound route" from "fails for some
            // other reason", and that distinction is the whole point of the test.
            assertThatThrownBy(() -> workflow.run(routeless))
                    .isInstanceOf(WorkflowFailedException.class)
                    .satisfies(ex -> assertThat(causeChain(ex)).contains("route is required"));
        }
    }

    private static String causeChain(Throwable throwable) {
        var builder = new StringBuilder();
        for (var current = throwable; current != null; current = current.getCause()) {
            builder.append(current.getMessage()).append(" | ");
        }
        return builder.toString();
    }

    private static String readFixture(String resource) {
        try (var in = Objects.requireNonNull(
                ImplementWorkflowUpgradeReplayTest.class.getClassLoader().getResourceAsStream(resource),
                "missing test fixture: " + resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
