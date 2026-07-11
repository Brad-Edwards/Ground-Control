package com.keplerops.groundcontrol.unit.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementActivities;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementContentActivities;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflow;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.ImplementWorkflowImpl;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CancelSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CapDisposition;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementChangeInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementChangeResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementOutcome;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementPhase;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.PrState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveRepositoryBindingInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RetryFromSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewCapDispositionSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewVerdict;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewerKind;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Temporal test-environment coverage for the deterministic {@code /implement} workflow (GC-O009 phase
 * 2 acceptance criteria): the full A-E phase graph executes end-to-end, the gate order matches the
 * GC-O007/ADR-029 contract, execution history replays deterministically, operator signals (cancel,
 * retry-from, review-cap disposition) are handled, transient activity failures are retried, and the
 * run survives a worker restart (crash/resume). Activities are deterministic doubles registered on the
 * worker; the workflow logic under test is real.
 */
class ImplementWorkflowReplayTest {

    private static final String TASK_QUEUE = "gc-implement-test";

    private static ImplementWorkflowInput input() {
        return new ImplementWorkflowInput("proj", 42, "make check", "sonar-key", 1, List.of("GC-O009"), 1);
    }

    private static WorkflowOptions options() {
        return WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("gc-impl-" + UUID.randomUUID())
                .build();
    }

    @Test
    void fullPhaseGraphReachesMergedAndReconciled() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            registerAndStart(env, activities, content);

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            ImplementWorkflowResult result = workflow.run(input());

            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
            assertThat(result.terminalPhase()).isEqualTo(ImplementPhase.E_POST_MERGE_RECONCILE);
            assertThat(result.reconciled()).isTrue();
            assertThat(result.prNumber()).isEqualTo(101);
        }
    }

    @Test
    void blocksAtReadinessUntilTheAuthoritativeMergeEventIsObserved() {
        // GC-O009 (b) acceptance criterion: the workflow blocks on merge and resumes on the
        // authoritative GitHub merge event. allowMerge=false simulates "PR not yet merged"; flipping it
        // simulates the merge being observed by the polling merge-observation activity.
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.allowMerge = false; // the merge gate has not observed a merge yet.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());

            // Advances through the ship pipeline to readiness, then holds at the merge gate — no operator
            // signal unblocks it, only observing the merge does.
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.READY_FOR_REVIEW);
            assertThat(workflow.currentPhase()).isEqualTo(ImplementPhase.D_SHIP_PIPELINE);
            // The bounded gate-state read model reports the run is blocked on the human merge gate.
            var gateState = workflow.gateState();
            assertThat(gateState.waitingForMerge()).isTrue();
            assertThat(gateState.phase()).isEqualTo(ImplementPhase.D_SHIP_PIPELINE);
            assertThat(gateState.escalatedPhase()).isNull();

            // Simulate the authoritative GitHub merge event; the next polled observation reports merged.
            activities.allowMerge = true;

            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
            assertThat(result.terminalPhase()).isEqualTo(ImplementPhase.E_POST_MERGE_RECONCILE);
            assertThat(result.reconciled()).isTrue();
        }
    }

    @Test
    void gateOrderMatchesContract() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            registerAndStart(env, activities, content);

            env.getWorkflowClient()
                    .newWorkflowStub(ImplementWorkflow.class, options())
                    .run(input());

            List<String> firstSeen = distinctInOrder(FakeActivities.CALLS);
            assertThat(firstSeen)
                    .containsExactly(
                            "resolveRepositoryBinding",
                            "resolveIssue",
                            "authorPlan",
                            "implementChange",
                            "runCompletionGate",
                            "evaluateQualityGate",
                            "stageCommitPush",
                            "runCodexReview",
                            "openPullRequest",
                            "observeCi",
                            "evaluateSonarGate",
                            "runTestQualityReview",
                            "postReadinessRecord",
                            "observeMergeState",
                            "observeMergedArtifacts",
                            "transitionRequirementStatus",
                            "reconcileTraceability",
                            "postFinalReport",
                            "closeIssue");
        }
    }

    @Test
    void executionHistoryReplaysDeterministically() {
        String workflowId;
        WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            registerAndStart(env, new FakeActivities(), new FakeContentActivities());
            WorkflowOptions options = options();
            workflowId = options.getWorkflowId();
            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options);
            workflow.run(input());
            history = env.getWorkflowClient().fetchHistory(workflowId);
        }

        assertThatCode(() -> WorkflowReplayer.replayWorkflowExecution(history, ImplementWorkflowImpl.class))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelSignalYieldsCancelledOutcome() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.allowMerge = false; // hold at the merge gate so the cancel arrives mid-wait.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());

            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.READY_FOR_REVIEW);
            workflow.cancel(new CancelSignal("operator abort"));

            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.CANCELLED);
        }
    }

    @Test
    void retryFromSignalRecoversFromEscalatedQualityGate() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.buildPasses = false; // Phase B completion gate fails -> workflow escalates and awaits retryFrom.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());

            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.B_QUALITY_GATE);
            activities.buildPasses = true;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.B_QUALITY_GATE, "fixed build"));

            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    @Test
    void retryFromForAnUnescalatedPhaseIsIgnored() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.buildPasses = false; // escalate at Phase B.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.B_QUALITY_GATE);

            // A retry bound to a different phase must not unblock the Phase B gate (stale-signal guard).
            activities.buildPasses = true;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.C_STAGE_COMMIT_PUSH, "wrong phase"));
            assertThat(workflow.currentOutcome()).isEqualTo(ImplementOutcome.ESCALATED);
            assertThat(workflow.currentPhase()).isEqualTo(ImplementPhase.B_QUALITY_GATE);

            // The correctly-bound retry resumes the run.
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.B_QUALITY_GATE, "matching phase"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    @Test
    void reviewCapDispositionProceedsPastDontShipVerdict() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            content.codexVerdict = ReviewVerdict.DONT_SHIP; // Phase C codex review blocks.
            registerAndStart(env, activities, content);

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());

            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.C_STAGE_COMMIT_PUSH);
            workflow.applyReviewCapDisposition(
                    new ReviewCapDispositionSignal(ReviewerKind.CODEX, CapDisposition.PROCEED));
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.C_STAGE_COMMIT_PUSH, "auto disposition"));

            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    @Test
    void reviewGateRetriesOnPhaseMatchingRetryWithoutDisposition() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            content.codexVerdict = ReviewVerdict.DONT_SHIP; // Phase C codex review blocks.
            registerAndStart(env, activities, content);

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.C_STAGE_COMMIT_PUSH);

            // A phase-matching retryFrom with NO disposition must re-run the review (RETRY branch), not
            // proceed. The re-review now passes, so the run completes — and the review activity ran twice.
            content.codexVerdict = ReviewVerdict.SHIP;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.C_STAGE_COMMIT_PUSH, "re-review"));

            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
            long codexRuns = FakeActivities.CALLS.stream()
                    .filter("runCodexReview"::equals)
                    .count();
            assertThat(codexRuns).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void shipWithFixesVerdictDoesNotPassTheReviewGate() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            content.codexVerdict = ReviewVerdict.SHIP_WITH_FIXES; // fixes still required — must not ship.
            registerAndStart(env, activities, content);

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());

            // The gate escalates at Phase C rather than proceeding to PR creation.
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.C_STAGE_COMMIT_PUSH);
            assertThat(FakeActivities.CALLS).doesNotContain("openPullRequest");

            workflow.cancel(new CancelSignal("done asserting"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.CANCELLED);
        }
    }

    @Test
    void transientActivityFailureIsRetried() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.failOpenPrTimes = 2; // first two openPullRequest attempts throw; retry policy recovers.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflowResult result = env.getWorkflowClient()
                    .newWorkflowStub(ImplementWorkflow.class, options())
                    .run(input());

            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
            assertThat(activities.openPrAttempts.get()).isEqualTo(3);
        }
    }

    @Test
    void ciFailureEscalatesAtShipPipelineAndRetryResumes() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.ciState = CiState.FAILURE;
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.D_SHIP_PIPELINE);

            activities.ciState = CiState.SUCCESS;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.D_SHIP_PIPELINE, "ci fixed"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    @Test
    void ciPendingIsPolledUntilResolved() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.ciPendingObservations = 2; // two PENDING polls before SUCCESS exercises the poll loop.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflowResult result = env.getWorkflowClient()
                    .newWorkflowStub(ImplementWorkflow.class, options())
                    .run(input());

            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
            assertThat(FakeActivities.CALLS.stream().filter("observeCi"::equals).count())
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void sonarErrorEscalatesAndPendingIsPolled() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.sonarStatus = SonarStatus.ERROR;
            activities.sonarPendingObservations = 1; // one PENDING poll before the ERROR is returned.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.D_SHIP_PIPELINE);
            assertThat(FakeActivities.CALLS.stream()
                            .filter("evaluateSonarGate"::equals)
                            .count())
                    .isGreaterThanOrEqualTo(2);

            activities.sonarStatus = SonarStatus.OK;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.D_SHIP_PIPELINE, "sonar fixed"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    @Test
    void closedUnmergedPullRequestYieldsEscalatedWithoutReconciliation() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            activities.mergeReturnsClosed = true; // PR closed without merge — abandoned run.
            registerAndStart(env, activities, new FakeContentActivities());

            ImplementWorkflowResult result = env.getWorkflowClient()
                    .newWorkflowStub(ImplementWorkflow.class, options())
                    .run(input());

            assertThat(result.outcome()).isEqualTo(ImplementOutcome.ESCALATED);
            assertThat(result.reconciled()).isFalse();
            // Phase E never runs for an abandoned PR: no reconciliation or status transition.
            assertThat(FakeActivities.CALLS).doesNotContain("observeMergedArtifacts", "transitionRequirementStatus");
        }
    }

    @Test
    void shipVerdictWithBlockingFindingsDoesNotPassTheReviewGate() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            FakeActivities activities = new FakeActivities();
            FakeContentActivities content = new FakeContentActivities();
            content.codexVerdict = ReviewVerdict.SHIP;
            content.codexBlockingOverride = 1; // SHIP but with a blocking finding — must not ship.
            registerAndStart(env, activities, content);

            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options());
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.C_STAGE_COMMIT_PUSH);
            assertThat(FakeActivities.CALLS).doesNotContain("openPullRequest");

            workflow.cancel(new CancelSignal("done asserting"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.CANCELLED);
        }
    }

    @Test
    void inFlightRunResumesFromHistoryAfterCrash() throws Exception {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            // Pause the run mid-flight at the escalated Phase B gate (a durable condition-await).
            FakeActivities activities = new FakeActivities();
            activities.buildPasses = false;
            registerAndStart(env, activities, new FakeContentActivities());

            WorkflowOptions options = options();
            String workflowId = options.getWorkflowId();
            ImplementWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ImplementWorkflow.class, options);
            WorkflowClient.start(workflow::run, input());
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> workflow.currentOutcome() == ImplementOutcome.ESCALATED
                            && workflow.currentPhase() == ImplementPhase.B_QUALITY_GATE);

            // A crashing worker recovers by rebuilding the in-flight workflow's state from event
            // history alone; replaying the partial history on a fresh replayer proves it is
            // deterministic and resumable, not merely that a completed run replays.
            WorkflowExecutionHistory inFlightHistory = env.getWorkflowClient().fetchHistory(workflowId);
            WorkflowReplayer.replayWorkflowExecution(inFlightHistory, ImplementWorkflowImpl.class);

            // The live run then resumes to completion once the operator fixes the gate and retries.
            activities.buildPasses = true;
            workflow.retryFrom(new RetryFromSignal(ImplementPhase.B_QUALITY_GATE, "resumed after crash"));
            ImplementWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(ImplementWorkflowResult.class);
            assertThat(result.outcome()).isEqualTo(ImplementOutcome.MERGED);
        }
    }

    private static void registerAndStart(
            TestWorkflowEnvironment env, FakeActivities activities, FakeContentActivities content) {
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ImplementWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities, content);
        env.start();
    }

    private static List<String> distinctInOrder(List<String> calls) {
        List<String> result = new ArrayList<>();
        for (String call : calls) {
            if (!result.contains(call)) {
                result.add(call);
            }
        }
        return result;
    }

    /** Deterministic double for the deterministic activities; records call order and is configurable. */
    static final class FakeActivities implements ImplementActivities {
        static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

        volatile boolean allowMerge = true;
        volatile boolean buildPasses = true;
        volatile int failOpenPrTimes = 0;
        volatile CiState ciState = CiState.SUCCESS;
        volatile int ciPendingObservations = 0;
        volatile SonarStatus sonarStatus = SonarStatus.OK;
        volatile int sonarPendingObservations = 0;
        volatile boolean mergeReturnsClosed = false;
        final AtomicInteger openPrAttempts = new AtomicInteger();
        private final AtomicInteger mergeObservations = new AtomicInteger();
        private final AtomicInteger ciObservations = new AtomicInteger();
        private final AtomicInteger sonarObservations = new AtomicInteger();

        FakeActivities() {
            CALLS.clear();
        }

        @Override
        public RepositoryBinding resolveRepositoryBinding(ResolveRepositoryBindingInput in) {
            CALLS.add("resolveRepositoryBinding");
            return new RepositoryBinding("acme", "repo", "dev");
        }

        @Override
        public ResolveIssueResult resolveIssue(ResolveIssueInput in) {
            CALLS.add("resolveIssue");
            return new ResolveIssueResult(
                    in.issueNumber(),
                    in.issueNumber() + "-slug",
                    in.repository().baseBranch(),
                    in.requirementUids());
        }

        @Override
        public CompletionGateResult runCompletionGate(CompletionGateInput in) {
            CALLS.add("runCompletionGate");
            return new CompletionGateResult(buildPasses, buildPasses ? 0 : 1, "ok");
        }

        @Override
        public QualityGateResult evaluateQualityGate(QualityGateInput in) {
            CALLS.add("evaluateQualityGate");
            boolean passed = in.buildPassed() && in.changelogUpdated() && in.clauseMappingComplete();
            return new QualityGateResult(passed, List.of());
        }

        @Override
        public GitPublishResult stageCommitPush(GitPublishInput in) {
            CALLS.add("stageCommitPush");
            return new GitPublishResult("abc1234", true, 1);
        }

        @Override
        public OpenPullRequestResult openPullRequest(OpenPullRequestInput in) {
            CALLS.add("openPullRequest");
            if (openPrAttempts.getAndIncrement() < failOpenPrTimes) {
                throw new IllegalStateException("transient open-pr failure");
            }
            return new OpenPullRequestResult(101, "https://example/pr/101");
        }

        @Override
        public CiObservationResult observeCi(CiObservationInput in) {
            CALLS.add("observeCi");
            if (ciObservations.getAndIncrement() < ciPendingObservations) {
                return new CiObservationResult(CiState.PENDING, List.of());
            }
            return new CiObservationResult(ciState, ciState == CiState.FAILURE ? List.of("build") : List.of());
        }

        @Override
        public SonarGateResult evaluateSonarGate(SonarGateInput in) {
            CALLS.add("evaluateSonarGate");
            if (sonarObservations.getAndIncrement() < sonarPendingObservations) {
                return new SonarGateResult(SonarStatus.PENDING);
            }
            return new SonarGateResult(sonarStatus);
        }

        @Override
        public MergeObservationResult observeMergeState(MergeObservationInput in) {
            CALLS.add("observeMergeState");
            if (mergeReturnsClosed) {
                return new MergeObservationResult(false, PrState.CLOSED);
            }
            boolean merged = allowMerge && mergeObservations.incrementAndGet() >= 1;
            return new MergeObservationResult(merged, merged ? PrState.MERGED : PrState.OPEN);
        }

        @Override
        public MergedArtifactsResult observeMergedArtifacts(MergedArtifactsInput in) {
            CALLS.add("observeMergedArtifacts");
            return new MergedArtifactsResult(
                    List.of("backend/src/main/java/App.java"), List.of("backend/src/test/java/AppTest.java"));
        }

        @Override
        public StatusTransitionResult transitionRequirementStatus(StatusTransitionInput in) {
            CALLS.add("transitionRequirementStatus");
            return new StatusTransitionResult(in.requirementUid(), Status.ACTIVE, true);
        }

        @Override
        public TraceabilityReconcileResult reconcileTraceability(TraceabilityReconcileInput in) {
            CALLS.add("reconcileTraceability");
            return new TraceabilityReconcileResult(
                    in.implementsArtifacts().size(), in.testsArtifacts().size());
        }

        @Override
        public CloseIssueResult closeIssue(CloseIssueInput in) {
            CALLS.add("closeIssue");
            return new CloseIssueResult(true, false);
        }
    }

    /** Deterministic double for the content-activity seam. */
    static final class FakeContentActivities implements ImplementContentActivities {
        volatile boolean testClean = true;
        volatile ReviewVerdict codexVerdict = ReviewVerdict.SHIP;
        // -1 derives blockingFindings from the verdict; >= 0 overrides it (verdict and blocking count are
        // independent schema fields, so the gate must check both).
        volatile int codexBlockingOverride = -1;

        @Override
        public AuthorPlanResult authorPlan(AuthorPlanInput in) {
            FakeActivities.CALLS.add("authorPlan");
            return new AuthorPlanResult(true, 1);
        }

        @Override
        public ImplementChangeResult implementChange(ImplementChangeInput in) {
            FakeActivities.CALLS.add("implementChange");
            return new ImplementChangeResult(1, 1, true, true);
        }

        @Override
        public CodexReviewResult runCodexReview(CodexReviewInput in) {
            FakeActivities.CALLS.add("runCodexReview");
            int blocking =
                    codexBlockingOverride >= 0 ? codexBlockingOverride : (codexVerdict == ReviewVerdict.SHIP ? 0 : 1);
            return new CodexReviewResult(codexVerdict, blocking, 1);
        }

        @Override
        public TestQualityReviewResult runTestQualityReview(TestQualityReviewInput in) {
            FakeActivities.CALLS.add("runTestQualityReview");
            return new TestQualityReviewResult(testClean, testClean ? 0 : 1, 1);
        }

        @Override
        public ReadinessRecordResult postReadinessRecord(ReadinessRecordInput in) {
            FakeActivities.CALLS.add("postReadinessRecord");
            return new ReadinessRecordResult(true, 1);
        }

        @Override
        public FinalReportResult postFinalReport(FinalReportInput in) {
            FakeActivities.CALLS.add("postFinalReport");
            return new FinalReportResult(true, 1);
        }
    }
}
