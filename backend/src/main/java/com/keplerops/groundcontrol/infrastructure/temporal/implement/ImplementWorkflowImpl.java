package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CancelSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CapDisposition;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishInput;
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
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.PrState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveRepositoryBindingInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RetryFromSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewCapDispositionSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewVerdict;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewerKind;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Deterministic core {@code /implement} workflow implementation (GC-O009 phase 2). Contains no
 * repositories, REST/GitHub clients, filesystem, clock, random/UUID, or LLM calls — only activity
 * invocations, Temporal timers, workflow state, and signal handlers (ADR-028 determinism boundary).
 *
 * <p>{@link #run} sequences the GC-O007/ADR-029 phase graph A-E in order, delegating each phase's gate
 * loop to a helper that returns a terminal result when the run stops (cancel / escalation-abandon) or
 * empty to advance. No plan-approval gate; PR merge is observed, never signaled. On a gate failure the
 * run escalates and awaits a phase-bound {@code retryFrom} or {@code cancel} signal (the durable pause).
 */
public class ImplementWorkflowImpl implements ImplementWorkflow {

    private static final int DEFAULT_REVIEW_CAP = 1;
    private static final int DEFAULT_POLL_SECONDS = 60;

    private final ImplementActivities activities =
            Workflow.newActivityStub(ImplementActivities.class, ImplementActivityOptions.standard());
    private final ImplementActivities buildActivities =
            Workflow.newActivityStub(ImplementActivities.class, ImplementActivityOptions.longRunning());
    private final ImplementContentActivities content =
            Workflow.newActivityStub(ImplementContentActivities.class, ImplementActivityOptions.longRunning());

    private ImplementPhase phase = ImplementPhase.A_PLAN_IMPLEMENT;
    private ImplementOutcome outcome;
    private boolean cancelled;
    private RetryFromSignal pendingRetry;
    private ReviewCapDispositionSignal lastDisposition;
    // The gate currently paused awaiting an operator signal (null when the run is not escalated).
    // Operator signals are bound to this gate so a stale, duplicated, or wrong-target signal cannot be
    // consumed by a later gate without a fresh decision.
    private ImplementPhase escalatedPhase;
    private ReviewerKind escalatedReviewer;

    private enum ReviewGateResolution {
        PROCEED,
        RETRY,
        CANCELLED
    }

    /** Immutable per-run parameters derived once from the workflow input. */
    private record RunContext(
            int issue,
            int cap,
            Duration poll,
            String title,
            String commitMessage,
            String runKey,
            String project,
            String completionCommand,
            String sonarProjectKey,
            List<String> requirementUids) {

        static RunContext from(ImplementWorkflowInput input) {
            int issue = input.issueNumber();
            String label = "Implement issue #" + issue;
            int cap = input.reviewCap() == null ? DEFAULT_REVIEW_CAP : input.reviewCap();
            long pollSeconds = input.pollIntervalSeconds() == null ? DEFAULT_POLL_SECONDS : input.pollIntervalSeconds();
            return new RunContext(
                    issue,
                    cap,
                    Duration.ofSeconds(pollSeconds),
                    label,
                    label,
                    Workflow.getInfo().getWorkflowId(),
                    input.project(),
                    input.completionCommand(),
                    input.sonarProjectKey(),
                    input.requirementUids());
        }
    }

    @Override
    public ImplementWorkflowResult run(ImplementWorkflowInput input) {
        Logger log = Workflow.getLogger(ImplementWorkflowImpl.class);
        RunContext ctx = RunContext.from(input);
        log.info("implement workflow start issue={} project={}", ctx.issue(), ctx.project());

        // Phase A: resolve the project-scoped repository binding (privileged GitHub side effects never
        // trust caller-supplied repo coordinates, ADR-028), plan + implement (content seams), and
        // resolve the issue's feature branch.
        phase = ImplementPhase.A_PLAN_IMPLEMENT;
        RepositoryBinding repository =
                activities.resolveRepositoryBinding(new ResolveRepositoryBindingInput(ctx.project()));
        ResolveIssueResult resolved =
                activities.resolveIssue(new ResolveIssueInput(repository, ctx.issue(), ctx.requirementUids()));
        content.authorPlan(new AuthorPlanInput(ctx.issue(), ctx.requirementUids(), ctx.runKey() + ":plan"));
        ImplementChangeResult change =
                content.implementChange(new ImplementChangeInput(ctx.issue(), null, ctx.runKey() + ":change"));
        if (cancelled) {
            return terminal(ctx.issue(), null, ImplementOutcome.CANCELLED, false);
        }

        Optional<ImplementWorkflowResult> stopped = runQualityGate(ctx, change);
        if (stopped.isPresent()) {
            return stopped.get();
        }
        stopped = stageAndCodexReview(ctx, resolved);
        if (stopped.isPresent()) {
            return stopped.get();
        }

        phase = ImplementPhase.D_SHIP_PIPELINE;
        int prNumber = activities
                .openPullRequest(
                        new OpenPullRequestInput(repository, resolved.branch(), ctx.title(), ctx.runKey() + ":pr"))
                .prNumber();
        stopped = awaitCi(ctx, repository, prNumber);
        if (stopped.isPresent()) {
            return stopped.get();
        }
        stopped = awaitSonarGate(ctx, prNumber);
        if (stopped.isPresent()) {
            return stopped.get();
        }
        stopped = runTestQualityGate(ctx, prNumber);
        if (stopped.isPresent()) {
            return stopped.get();
        }

        content.postReadinessRecord(new ReadinessRecordInput(ctx.issue(), prNumber, ctx.runKey() + ":readiness"));
        outcome = ImplementOutcome.READY_FOR_REVIEW; // Phase D terminal signal; now await the human merge gate.
        log.info("implement workflow ready-for-review issue={} pr={}", ctx.issue(), prNumber);

        stopped = awaitMerge(ctx, repository, prNumber);
        if (stopped.isPresent()) {
            return stopped.get();
        }
        return postMergeReconcile(ctx, resolved, repository, prNumber);
    }

    /** Phase B: hard completion gate — build + CHANGELOG + clause mapping. */
    private Optional<ImplementWorkflowResult> runQualityGate(RunContext ctx, ImplementChangeResult change) {
        phase = ImplementPhase.B_QUALITY_GATE;
        while (true) {
            CompletionGateResult build =
                    buildActivities.runCompletionGate(new CompletionGateInput(ctx.completionCommand()));
            QualityGateResult gate = activities.evaluateQualityGate(new QualityGateInput(
                    ctx.project(), build.passed(), change.changelogUpdated(), change.clauseMappingComplete()));
            if (gate.passed()) {
                return Optional.empty();
            }
            if (!escalateAndAwait()) {
                return Optional.of(terminal(ctx.issue(), null, ImplementOutcome.CANCELLED, false));
            }
        }
    }

    /** Phase C: stage/commit/push + single pre-push codex review pass. */
    private Optional<ImplementWorkflowResult> stageAndCodexReview(RunContext ctx, ResolveIssueResult resolved) {
        phase = ImplementPhase.C_STAGE_COMMIT_PUSH;
        activities.stageCommitPush(
                new GitPublishInput(resolved.branch(), ctx.commitMessage(), null, ctx.runKey() + ":push"));
        // The gate passes only on a clean review (SHIP with zero blocking findings). Any other verdict —
        // including SHIP_WITH_FIXES, which means fixes are still required — must not silently ship: it
        // escalates and awaits an operator PROCEED disposition or a retry (GC-O007 review gate).
        ReviewGateResolution decision = ReviewGateResolution.RETRY;
        while (decision == ReviewGateResolution.RETRY) {
            CodexReviewResult review = content.runCodexReview(new CodexReviewInput(ctx.issue(), ctx.cap()));
            boolean clean = review.verdict() == ReviewVerdict.SHIP && review.blockingFindings() == 0;
            decision = clean ? ReviewGateResolution.PROCEED : escalateReviewGate(ReviewerKind.CODEX);
        }
        return decision == ReviewGateResolution.CANCELLED
                ? Optional.of(terminal(ctx.issue(), null, ImplementOutcome.CANCELLED, false))
                : Optional.empty();
    }

    private Optional<ImplementWorkflowResult> awaitCi(RunContext ctx, RepositoryBinding repository, int prNumber) {
        while (true) {
            CiObservationResult ci = pollCi(repository, prNumber, ctx.poll());
            if (cancelled) {
                return Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.CANCELLED, false));
            }
            if (ci.state() == CiState.SUCCESS) {
                return Optional.empty();
            }
            if (!escalateAndAwait()) {
                return Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.CANCELLED, false));
            }
        }
    }

    private Optional<ImplementWorkflowResult> awaitSonarGate(RunContext ctx, int prNumber) {
        if (ctx.sonarProjectKey() == null) {
            return Optional.empty();
        }
        while (true) {
            SonarStatus status = activities
                    .evaluateSonarGate(new SonarGateInput(ctx.sonarProjectKey(), prNumber))
                    .status();
            if (status == SonarStatus.OK || status == SonarStatus.NONE) {
                return Optional.empty();
            }
            // PENDING waits and re-observes; any failing status escalates for an operator decision. Both
            // funnel through a single boolean so the loop keeps one exit path (no break/continue).
            boolean advanced = status == SonarStatus.PENDING ? sleepUnlessCancelled(ctx.poll()) : escalateAndAwait();
            if (!advanced) {
                return Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.CANCELLED, false));
            }
        }
    }

    private Optional<ImplementWorkflowResult> runTestQualityGate(RunContext ctx, int prNumber) {
        ReviewGateResolution decision = ReviewGateResolution.RETRY;
        while (decision == ReviewGateResolution.RETRY) {
            boolean clean = content.runTestQualityReview(new TestQualityReviewInput(ctx.issue(), ctx.cap()))
                    .clean();
            decision = clean ? ReviewGateResolution.PROCEED : escalateReviewGate(ReviewerKind.TEST_QUALITY);
        }
        return decision == ReviewGateResolution.CANCELLED
                ? Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.CANCELLED, false))
                : Optional.empty();
    }

    /** The single synchronous human gate: PR merge, observed from GitHub (never a Temporal signal). */
    private Optional<ImplementWorkflowResult> awaitMerge(RunContext ctx, RepositoryBinding repository, int prNumber) {
        while (true) {
            if (cancelled) {
                return Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.CANCELLED, false));
            }
            MergeObservationResult merge =
                    activities.observeMergeState(new MergeObservationInput(repository, prNumber));
            if (merge.merged()) {
                return Optional.empty();
            }
            if (merge.prState() == PrState.CLOSED) {
                // Closed without merge: abandoned. Ground Control state stays unreconciled (GC-O007 E).
                return Optional.of(terminal(ctx.issue(), prNumber, ImplementOutcome.ESCALATED, false));
            }
            Workflow.await(ctx.poll(), () -> cancelled);
        }
    }

    /** Phase E: post-merge reconciliation — transition, reconcile traceability, final report, close. */
    private ImplementWorkflowResult postMergeReconcile(
            RunContext ctx, ResolveIssueResult resolved, RepositoryBinding repository, int prNumber) {
        phase = ImplementPhase.E_POST_MERGE_RECONCILE;
        MergedArtifactsResult artifacts =
                activities.observeMergedArtifacts(new MergedArtifactsInput(repository, prNumber));
        for (String uid : resolved.requirementUids()) {
            activities.transitionRequirementStatus(new StatusTransitionInput(Status.ACTIVE, ctx.project(), uid));
        }
        activities.reconcileTraceability(new TraceabilityReconcileInput(
                ctx.project(),
                resolved.requirementUids(),
                prNumber,
                artifacts.implementsArtifacts(),
                artifacts.testsArtifacts(),
                ctx.runKey() + ":reconcile"));
        content.postFinalReport(new FinalReportInput(
                ctx.issue(), prNumber, resolved.requirementUids(), ctx.runKey() + ":final-report"));
        activities.closeIssue(new CloseIssueInput(repository, ctx.issue(), prNumber, ctx.runKey() + ":close"));
        Workflow.getLogger(ImplementWorkflowImpl.class)
                .info("implement workflow merged+reconciled issue={} pr={}", ctx.issue(), prNumber);
        return terminal(ctx.issue(), prNumber, ImplementOutcome.MERGED, true);
    }

    private CiObservationResult pollCi(RepositoryBinding repository, int prNumber, Duration poll) {
        CiObservationResult ci = activities.observeCi(new CiObservationInput(repository, prNumber));
        while (ci.state() == CiState.PENDING && !cancelled) {
            if (sleepUnlessCancelled(poll)) {
                ci = activities.observeCi(new CiObservationInput(repository, prNumber));
            }
        }
        return ci;
    }

    /** Sleep for {@code poll}, waking early on cancel. Returns {@code false} if cancelled. */
    private boolean sleepUnlessCancelled(Duration poll) {
        Workflow.await(poll, () -> cancelled);
        return !cancelled;
    }

    /**
     * Escalate a non-review gate (quality gate, CI, Sonar): mark the run ESCALATED, bind the pause to the
     * current phase, and await a matching operator {@code retryFrom} or a {@code cancel} (the durable
     * pause). Any stale/wrong-phase retry is cleared on entry so only a fresh, phase-matching decision
     * unblocks. Returns {@code true} to retry the gate, {@code false} when cancelled.
     */
    private boolean escalateAndAwait() {
        outcome = ImplementOutcome.ESCALATED;
        escalatedPhase = phase;
        escalatedReviewer = null;
        pendingRetry = null;
        Workflow.await(() -> cancelled || retryMatchesEscalatedGate());
        escalatedPhase = null;
        if (cancelled) {
            return false;
        }
        outcome = null;
        return true;
    }

    /**
     * Escalate a review gate for {@code reviewer}: await either a phase-matching {@code retryFrom}
     * (re-run the review), a PROCEED review-cap disposition for this same reviewer, or {@code cancel}.
     * Stale retries and dispositions are cleared on entry so a signal recorded outside this gate's
     * escalation cannot resolve it.
     */
    private ReviewGateResolution escalateReviewGate(ReviewerKind reviewer) {
        outcome = ImplementOutcome.ESCALATED;
        escalatedPhase = phase;
        escalatedReviewer = reviewer;
        pendingRetry = null;
        lastDisposition = null;
        Workflow.await(() -> cancelled || retryMatchesEscalatedGate() || proceedDispositionForEscalatedReviewer());
        boolean proceed = !cancelled && proceedDispositionForEscalatedReviewer();
        escalatedPhase = null;
        escalatedReviewer = null;
        if (cancelled) {
            return ReviewGateResolution.CANCELLED;
        }
        outcome = null;
        return proceed ? ReviewGateResolution.PROCEED : ReviewGateResolution.RETRY;
    }

    private boolean retryMatchesEscalatedGate() {
        return pendingRetry != null && escalatedPhase != null && pendingRetry.phase() == escalatedPhase;
    }

    private boolean proceedDispositionForEscalatedReviewer() {
        return lastDisposition != null
                && escalatedReviewer != null
                && lastDisposition.reviewer() == escalatedReviewer
                && lastDisposition.disposition() == CapDisposition.PROCEED;
    }

    private ImplementWorkflowResult terminal(int issue, Integer prNumber, ImplementOutcome result, boolean reconciled) {
        outcome = result;
        return new ImplementWorkflowResult(issue, phase, result, prNumber, reconciled);
    }

    @Override
    public void cancel(CancelSignal signal) {
        cancelled = true;
    }

    @Override
    public void retryFrom(RetryFromSignal signal) {
        // Bind the retry to the gate currently escalated. A stale, duplicated, or wrong-phase retry is
        // ignored so it cannot later auto-unblock a different gate without a fresh operator decision.
        if (escalatedPhase != null && signal.phase() == escalatedPhase) {
            pendingRetry = signal;
        }
    }

    @Override
    public void applyReviewCapDisposition(ReviewCapDispositionSignal signal) {
        // Only a disposition for the reviewer whose review gate is currently escalated is accepted; a
        // disposition for another reviewer or sent outside an escalated review gate is ignored.
        if (escalatedReviewer != null && signal.reviewer() == escalatedReviewer) {
            lastDisposition = signal;
        }
    }

    @Override
    public ImplementPhase currentPhase() {
        return phase;
    }

    @Override
    public ImplementOutcome currentOutcome() {
        return outcome;
    }
}
