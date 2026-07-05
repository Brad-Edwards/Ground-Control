package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CiObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CloseIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergeObservationResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.MergedArtifactsResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.OpenPullRequestResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.QualityGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RepositoryBinding;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveIssueResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolveRepositoryBindingInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.StatusTransitionResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TraceabilityReconcileResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Deterministic {@code /implement} activities (GC-O009 phase 2). Each activity is pure API
 * orchestration over existing domain services and infrastructure ports with typed, schema-backed I/O
 * (see {@code contracts/schemas/workflow/}); none imports an LLM provider (ADR-028 determinism boundary).
 *
 * <p>The content/LLM/review steps of the A-E graph live behind the separate seam interface
 * {@link ImplementContentActivities}; the workflow sequences both, and the deterministic gate order
 * (GC-O007/ADR-029) is expressed by {@link ImplementWorkflowImpl}.
 */
@ActivityInterface
public interface ImplementActivities {

    @ActivityMethod
    RepositoryBinding resolveRepositoryBinding(ResolveRepositoryBindingInput input);

    @ActivityMethod
    ResolveIssueResult resolveIssue(ResolveIssueInput input);

    @ActivityMethod
    CompletionGateResult runCompletionGate(CompletionGateInput input);

    @ActivityMethod
    QualityGateResult evaluateQualityGate(QualityGateInput input);

    @ActivityMethod
    GitPublishResult stageCommitPush(GitPublishInput input);

    @ActivityMethod
    OpenPullRequestResult openPullRequest(OpenPullRequestInput input);

    @ActivityMethod
    CiObservationResult observeCi(CiObservationInput input);

    @ActivityMethod
    SonarGateResult evaluateSonarGate(SonarGateInput input);

    @ActivityMethod
    MergeObservationResult observeMergeState(MergeObservationInput input);

    @ActivityMethod
    MergedArtifactsResult observeMergedArtifacts(MergedArtifactsInput input);

    @ActivityMethod
    StatusTransitionResult transitionRequirementStatus(StatusTransitionInput input);

    @ActivityMethod
    TraceabilityReconcileResult reconcileTraceability(TraceabilityReconcileInput input);

    @ActivityMethod
    CloseIssueResult closeIssue(CloseIssueInput input);
}
