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
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestResult;
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
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * Deterministic core {@code /implement} workflow implementation (GC-O009 phase 2). Contains no
 * repositories, REST/GitHub clients, filesystem, clock, random/UUID, or LLM calls — only activity
 * invocations, Temporal timers, workflow state, and signal handlers (ADR-028 determinism boundary).
 *
 * <p>The method body is the GC-O007/ADR-029 phase graph in order: A plan+implement, B hard quality
 * gate, C stage/commit/push + pre-push codex review, D ship pipeline (PR, CI, Sonar, test-quality
 * review, readiness record) then wait for the observed merge, E post-merge reconciliation. No
 * plan-approval gate; PR merge is observed, never signaled. On a gate failure the run escalates and
 * awaits a {@code retryFrom} or {@code cancel} signal (the durable operator pause).
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

    @Override
    public ImplementWorkflowResult run(ImplementWorkflowInput input) {
        Logger log = Workflow.getLogger(ImplementWorkflowImpl.class);
        int issue = input.issueNumber();
        int cap = input.reviewCap() == null ? DEFAULT_REVIEW_CAP : input.reviewCap();
        Duration poll = Duration.ofSeconds(
                input.pollIntervalSeconds() == null ? DEFAULT_POLL_SECONDS : input.pollIntervalSeconds());
        String title = "Implement issue #" + issue;
        String commitMessage = "Implement issue #" + issue;
        // Stable per-run key base; each mutating activity derives a fixed idempotency key from it so an
        // at-least-once Temporal retry observes-before-creates rather than duplicating a side effect.
        String runKey = Workflow.getInfo().getWorkflowId();
        log.info("implement workflow start issue={} project={}", issue, input.project());

        // Phase A: resolve the project-scoped repository binding (privileged GitHub side effects never
        // trust caller-supplied repo coordinates, ADR-028), then plan + implement (content seams) and
        // resolve the issue's feature branch.
        phase = ImplementPhase.A_PLAN_IMPLEMENT;
        RepositoryBinding repository =
                activities.resolveRepositoryBinding(new ResolveRepositoryBindingInput(input.project()));
        ResolveIssueResult resolved =
                activities.resolveIssue(new ResolveIssueInput(repository, issue, input.requirementUids()));
        content.authorPlan(new AuthorPlanInput(issue, input.requirementUids(), runKey + ":plan"));
        ImplementChangeResult change =
                content.implementChange(new ImplementChangeInput(issue, null, runKey + ":change"));
        if (cancelled) {
            return terminal(issue, null, ImplementOutcome.CANCELLED, false);
        }

        // Phase B: hard completion gate — build + CHANGELOG + clause mapping.
        phase = ImplementPhase.B_QUALITY_GATE;
        while (true) {
            CompletionGateResult build =
                    buildActivities.runCompletionGate(new CompletionGateInput(input.completionCommand()));
            QualityGateResult gate = activities.evaluateQualityGate(new QualityGateInput(
                    input.project(), build.passed(), change.changelogUpdated(), change.clauseMappingComplete()));
            if (gate.passed()) {
                break;
            }
            if (!escalateAndAwait()) {
                return terminal(issue, null, ImplementOutcome.CANCELLED, false);
            }
        }

        // Phase C: stage/commit/push + single pre-push codex review pass.
        phase = ImplementPhase.C_STAGE_COMMIT_PUSH;
        activities.stageCommitPush(new GitPublishInput(resolved.branch(), commitMessage, null, runKey + ":push"));
        while (true) {
            CodexReviewResult review = content.runCodexReview(new CodexReviewInput(issue, cap));
            // The gate passes only on a clean review (SHIP with zero blocking findings). Any other
            // verdict — including SHIP_WITH_FIXES, which means fixes are still required — must not
            // silently ship: it escalates and awaits an operator PROCEED disposition or retry (GC-O007).
            boolean clean = review.verdict() == ReviewVerdict.SHIP && review.blockingFindings() == 0;
            if (clean) {
                break;
            }
            ReviewGateResolution decision = escalateReviewGate(ReviewerKind.CODEX);
            if (decision == ReviewGateResolution.CANCELLED) {
                return terminal(issue, null, ImplementOutcome.CANCELLED, false);
            }
            if (decision == ReviewGateResolution.PROCEED) {
                break;
            }
        }

        // Phase D: ship pipeline — PR, CI, Sonar, test-quality review, readiness; then await merge.
        phase = ImplementPhase.D_SHIP_PIPELINE;
        OpenPullRequestResult pr = activities.openPullRequest(
                new OpenPullRequestInput(repository, resolved.branch(), title, runKey + ":pr"));
        int prNumber = pr.prNumber();

        while (true) {
            CiObservationResult ci = pollCi(repository, prNumber, poll);
            if (cancelled) {
                return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
            }
            if (ci.state() == CiState.SUCCESS) {
                break;
            }
            if (!escalateAndAwait()) {
                return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
            }
        }

        while (input.sonarProjectKey() != null) {
            SonarGateResult sonar = activities.evaluateSonarGate(new SonarGateInput(input.sonarProjectKey(), prNumber));
            if (sonar.status() == SonarStatus.OK || sonar.status() == SonarStatus.NONE) {
                break;
            }
            if (sonar.status() == SonarStatus.PENDING) {
                if (!sleepUnlessCancelled(poll)) {
                    return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
                }
                continue;
            }
            if (!escalateAndAwait()) {
                return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
            }
        }

        while (true) {
            TestQualityReviewResult tq = content.runTestQualityReview(new TestQualityReviewInput(issue, cap));
            if (tq.clean()) {
                break;
            }
            ReviewGateResolution decision = escalateReviewGate(ReviewerKind.TEST_QUALITY);
            if (decision == ReviewGateResolution.CANCELLED) {
                return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
            }
            if (decision == ReviewGateResolution.PROCEED) {
                break;
            }
        }

        content.postReadinessRecord(new ReadinessRecordInput(issue, prNumber, runKey + ":readiness"));
        outcome = ImplementOutcome.READY_FOR_REVIEW; // Phase D terminal signal; now await the human merge gate.
        log.info("implement workflow ready-for-review issue={} pr={}", issue, prNumber);

        // The single synchronous human gate: PR merge, observed from GitHub (never a Temporal signal).
        while (true) {
            if (cancelled) {
                return terminal(issue, prNumber, ImplementOutcome.CANCELLED, false);
            }
            MergeObservationResult merge =
                    activities.observeMergeState(new MergeObservationInput(repository, prNumber));
            if (merge.merged()) {
                break;
            }
            if (merge.prState() == PrState.CLOSED) {
                // Closed without merge: abandoned. Ground Control state stays unreconciled (GC-O007 E).
                return terminal(issue, prNumber, ImplementOutcome.ESCALATED, false);
            }
            Workflow.await(poll, () -> cancelled);
        }

        // Phase E: post-merge reconciliation — transition, reconcile traceability, final report, close.
        phase = ImplementPhase.E_POST_MERGE_RECONCILE;
        MergedArtifactsResult artifacts =
                activities.observeMergedArtifacts(new MergedArtifactsInput(repository, prNumber));
        for (String uid : resolved.requirementUids()) {
            activities.transitionRequirementStatus(new StatusTransitionInput(Status.ACTIVE, input.project(), uid));
        }
        activities.reconcileTraceability(new TraceabilityReconcileInput(
                input.project(),
                resolved.requirementUids(),
                prNumber,
                artifacts.implementsArtifacts(),
                artifacts.testsArtifacts(),
                runKey + ":reconcile"));
        content.postFinalReport(
                new FinalReportInput(issue, prNumber, resolved.requirementUids(), runKey + ":final-report"));
        activities.closeIssue(new CloseIssueInput(repository, issue, prNumber, runKey + ":close"));

        log.info("implement workflow merged+reconciled issue={} pr={}", issue, prNumber);
        return terminal(issue, prNumber, ImplementOutcome.MERGED, true);
    }

    private CiObservationResult pollCi(RepositoryBinding repository, int prNumber, Duration poll) {
        CiObservationResult ci = activities.observeCi(new CiObservationInput(repository, prNumber));
        while (ci.state() == CiState.PENDING && !cancelled) {
            Workflow.await(poll, () -> cancelled);
            if (cancelled) {
                break;
            }
            ci = activities.observeCi(new CiObservationInput(repository, prNumber));
        }
        return ci;
    }

    /** Sleep for {@code poll}, waking early on cancel. Returns {@code false} if cancelled. */
    private boolean sleepUnlessCancelled(Duration poll) {
        Workflow.await(poll, () -> cancelled);
        return !cancelled;
    }

    /**
     * Escalate a non-review gate (quality gate, CI, Sonar): mark the run ESCALATED, bind the pause to
     * the current phase, and await a matching operator {@code retryFrom} or a {@code cancel} (the
     * durable pause). Any stale/wrong-phase retry is cleared first so only a fresh, phase-matching
     * decision unblocks. Returns {@code true} to retry the gate, {@code false} when cancelled.
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
        pendingRetry = null;
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
        pendingRetry = null;
        lastDisposition = null;
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
